using System.Diagnostics;

namespace Relay.Core;

/// <summary>
/// Runs Full Mode's tunnel: starts <c>relaywg-client.exe</c>, hands it the
/// configuration, and stops it again.
///
/// The tunnel lives in a child process because creating a WinTun adapter and
/// changing routes needs Administrator, and Relay is a per-user install that
/// never asks for it anywhere else (ADR-0005, ADR-0008). This class is the
/// whole of the app's dealings with that privilege: everything above it works
/// in terms of "connected" and "disconnected".
///
/// Kept out of <c>Relay.App</c> and free of any UI type so it can be tested
/// against a stub process on a machine with no adapter and no elevation. What
/// only a real Windows machine can prove — that the adapter comes up and
/// carries traffic — is proven by the client's own test, on a runner.
/// </summary>
public sealed class WgTunnelSession(WgTunnelSession.IProcessHost host)
{
    /// <summary>Error codes are the stable identifiers from docs/errors.md.</summary>
    public sealed record Result(bool Ok, string? ErrorCode = null)
    {
        public static readonly Result Success = new(true);
        public static Result Fail(string code) => new(false, code);
    }

    /// <summary>
    /// The child process, behind an interface so the lifecycle can be tested
    /// without one. A test that has to launch a real elevated process to check
    /// "what happens when the person clicks No on the UAC prompt" is a test
    /// nobody can run.
    /// </summary>
    public interface IProcessHost
    {
        /// <summary>
        /// Starts the tunnel process. Throws <see cref="ElevationDeclined"/> if
        /// the person dismissed the UAC prompt.
        /// </summary>
        IProcessHandle Start(string arguments);
    }

    public interface IProcessHandle : IDisposable
    {
        /// <summary>Writes a line to the child's stdin.</summary>
        void WriteLine(string line);

        /// <summary>Reads one line of the child's stdout, or null at end of stream.</summary>
        string? ReadLine();

        /// <summary>Closes stdin, which is how the child is asked to stop.</summary>
        void CloseInput();

        /// <summary>Waits for exit; false if it did not within the timeout.</summary>
        bool WaitForExit(TimeSpan timeout);

        /// <summary>Last resort when it will not leave on its own.</summary>
        void Kill();

        bool HasExited { get; }
    }

    /// <summary>Thrown when the person answered No to the elevation prompt.</summary>
    public sealed class ElevationDeclined(Exception? inner = null)
        : Exception("The elevation prompt was declined", inner);

    /// <summary>What the client prints once traffic can flow.</summary>
    public const string ReadyLine = "READY";

    /// <summary>Ends the configuration without closing stdin. Must match the client.</summary>
    public const string ConfigTerminator = "END-CONFIG";

    /// <summary>How long to wait for the adapter. Creating one is slow the first time.</summary>
    public static readonly TimeSpan ReadyTimeout = TimeSpan.FromSeconds(45);

    private IProcessHandle? _tunnel;

    public bool IsRunning => _tunnel is { HasExited: false };

    /// <summary>
    /// Brings the tunnel up from the QR's <c>wg</c> block.
    ///
    /// Nothing on this machine is changed before the child starts, and
    /// everything it changes goes away with it, so a failure at any point here
    /// needs no rollback of its own — which is why this reads so much simpler
    /// than <see cref="Proxy.ProxySession"/>, where Relay edits the registry
    /// itself and has to be able to put it back.
    /// </summary>
    public Result Connect(WgParams wg, string host)
    {
        if (IsRunning) return Result.Fail("ERR_WG_ALREADY_RUNNING");

        string config;
        try
        {
            config = WgClientConfig.ToIpc(wg, host);
        }
        catch (ArgumentException)
        {
            // The payload cannot produce a tunnel. Saying "the QR is unusable"
            // is honest; letting the child fail on it would report the same
            // thing as a tunnel that could not start, which sends someone
            // looking at their network for a fault in a QR code.
            return Result.Fail("ERR_QR_INVALID");
        }

        IProcessHandle tunnel;
        try
        {
            tunnel = host.Start(Arguments(wg));
        }
        catch (ElevationDeclined)
        {
            return Result.Fail("ERR_WG_ELEVATION_DECLINED");
        }
        catch (Exception)
        {
            return Result.Fail("ERR_WG_START_FAILED");
        }

        try
        {
            foreach (var line in config.Split('\n', StringSplitOptions.RemoveEmptyEntries))
            {
                tunnel.WriteLine(line);
            }
            tunnel.WriteLine(ConfigTerminator);
        }
        catch (Exception)
        {
            Stop(tunnel);
            return Result.Fail("ERR_WG_START_FAILED");
        }

        if (!WaitForReady(tunnel))
        {
            Stop(tunnel);
            return Result.Fail("ERR_WG_START_FAILED");
        }

        _tunnel = tunnel;
        return Result.Success;
    }

    /// <summary>
    /// Takes the tunnel down. Safe to call when nothing is running, and safe to
    /// call twice: it runs on paths that are already unwinding from a failure.
    /// </summary>
    public Result Disconnect()
    {
        var tunnel = _tunnel;
        _tunnel = null;
        if (tunnel is null) return Result.Success;

        return Stop(tunnel) ? Result.Success : Result.Fail("ERR_WG_STOP_FAILED");
    }

    /// <summary>
    /// Closing stdin is the ordinary way out; the kill is for a child that has
    /// wedged. Either way the adapter goes with the process, so "stopped" is a
    /// fact about the machine and not a hope.
    /// </summary>
    private static bool Stop(IProcessHandle tunnel)
    {
        try
        {
            if (!tunnel.HasExited)
            {
                tunnel.CloseInput();
                if (!tunnel.WaitForExit(TimeSpan.FromSeconds(10)))
                {
                    tunnel.Kill();
                    if (!tunnel.WaitForExit(TimeSpan.FromSeconds(5))) return false;
                }
            }
            return true;
        }
        catch (Exception)
        {
            return false;
        }
        finally
        {
            try { tunnel.Dispose(); } catch { /* teardown must not throw */ }
        }
    }

    private static bool WaitForReady(IProcessHandle tunnel)
    {
        var deadline = DateTimeOffset.UtcNow + ReadyTimeout;
        while (DateTimeOffset.UtcNow < deadline)
        {
            // Null means the child's stdout ended: it exited without ever being
            // ready, which is what a refused adapter looks like from here.
            var line = tunnel.ReadLine();
            if (line is null) return false;
            if (line.Trim() == ReadyLine) return true;
        }
        return false;
    }

    /// <summary>
    /// The client's arguments. The DNS server and the tunnel address come from
    /// the payload and the shared contract rather than being repeated here.
    /// </summary>
    internal static string Arguments(WgParams wg)
    {
        var arguments = new List<string>
        {
            "-name", "Relay",
            "-address", WgClientConfig.ClientTunnelAddress,
            "-routes", wg.AllowedIps,
        };
        if (!string.IsNullOrWhiteSpace(wg.Dns))
        {
            arguments.Add("-dns");
            arguments.Add(wg.Dns);
        }
        return string.Join(' ', arguments.Select(Quote));
    }

    private static string Quote(string argument) =>
        argument.Contains(' ') ? $"\"{argument}\"" : argument;
}

/// <summary>
/// The real <see cref="WgTunnelSession.IProcessHost"/>: raises the elevation
/// prompt for the tunnel process and talks to it over a private named pipe.
///
/// The pipe is the whole reason this is not simply a redirected child process.
/// A process started through the elevation prompt cannot have its streams
/// redirected — Windows' <c>runas</c> goes through ShellExecute, which has no
/// way to hand over a handle — so the choice is a pipe or writing the client's
/// private key to a temp file. A key on disk outlives the session, lands in
/// backups, and is readable by anything running as that user afterwards.
///
/// The pipe is created before the prompt is raised and its name is the only
/// thing on the command line, which any process on the machine can read: a name
/// is useless to anyone who cannot open it, and the pipe allows exactly one
/// connection and is closed the moment the tunnel stops.
/// </summary>
public sealed class ElevatedTunnelHost(string executablePath) : WgTunnelSession.IProcessHost
{
    /// <summary>Windows' code for "the user cancelled the elevation prompt".</summary>
    private const int ErrorCancelled = 1223;

    /// <summary>How long to wait for the elevated child to pick up the pipe.</summary>
    private static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(60);

    public WgTunnelSession.IProcessHandle Start(string arguments)
    {
        var name = "relay-wg-" + Guid.NewGuid().ToString("N");
        // One instance, and the elevated child is the only thing that will ever
        // connect. In only means "the app writes"; the client's readiness comes
        // back the other way, so this is duplex.
        var pipe = new System.IO.Pipes.NamedPipeServerStream(
            name,
            System.IO.Pipes.PipeDirection.InOut,
            maxNumberOfServerInstances: 1,
            System.IO.Pipes.PipeTransmissionMode.Byte,
            System.IO.Pipes.PipeOptions.Asynchronous);

        Process process;
        try
        {
            var info = new ProcessStartInfo(executablePath, $"{arguments} -config-pipe {name}")
            {
                UseShellExecute = true,
                Verb = "runas", // the elevation prompt, for this one process
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            process = Process.Start(info)
                ?? throw new InvalidOperationException("no process was started");
        }
        catch (System.ComponentModel.Win32Exception ex) when (ex.NativeErrorCode == ErrorCancelled)
        {
            pipe.Dispose();
            throw new WgTunnelSession.ElevationDeclined(ex);
        }
        catch
        {
            pipe.Dispose();
            throw;
        }

        try
        {
            // Waits for the child, and stops waiting if it dies first — an
            // elevated process that fails immediately would otherwise leave
            // this blocked on a connection that is never coming.
            var connected = pipe.WaitForConnectionAsync();
            var deadline = DateTimeOffset.UtcNow + ConnectTimeout;
            while (!connected.Wait(TimeSpan.FromMilliseconds(250)))
            {
                if (process.HasExited)
                {
                    throw new InvalidOperationException(
                        $"the tunnel process exited with {process.ExitCode} before connecting");
                }
                if (DateTimeOffset.UtcNow > deadline)
                {
                    throw new TimeoutException("the tunnel process never connected");
                }
            }
        }
        catch
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { }
            pipe.Dispose();
            process.Dispose();
            throw;
        }

        return new Handle(process, pipe);
    }

    private sealed class Handle : WgTunnelSession.IProcessHandle
    {
        private readonly Process _process;
        private readonly System.IO.Pipes.NamedPipeServerStream _pipe;
        private readonly StreamWriter _writer;
        private readonly StreamReader _reader;

        public Handle(Process process, System.IO.Pipes.NamedPipeServerStream pipe)
        {
            _process = process;
            _pipe = pipe;
            _writer = new StreamWriter(pipe) { AutoFlush = true, NewLine = "\n" };
            _reader = new StreamReader(pipe);
        }

        public bool HasExited => _process.HasExited;

        public void WriteLine(string line) => _writer.WriteLine(line);

        public string? ReadLine() => _reader.ReadLine();

        /// <summary>
        /// Closing the pipe is how the tunnel is told to stop — and because the
        /// pipe dies with this process too, a crash of the app stops the tunnel
        /// just as reliably as a Disconnect does.
        /// </summary>
        public void CloseInput() => _pipe.Dispose();

        public bool WaitForExit(TimeSpan timeout) => _process.WaitForExit((int)timeout.TotalMilliseconds);

        public void Kill() => _process.Kill(entireProcessTree: true);

        public void Dispose()
        {
            try { _pipe.Dispose(); } catch { }
            try { _process.Dispose(); } catch { }
        }
    }
}
