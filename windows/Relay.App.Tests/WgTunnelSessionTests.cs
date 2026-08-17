using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// The tunnel's lifecycle, against a stub process.
///
/// What a machine with no adapter and no elevation can still prove is most of
/// what goes wrong in practice: that the configuration reaches the child in the
/// form it expects, that a declined elevation prompt is reported as itself
/// rather than as a broken tunnel, that a child which never becomes ready is
/// not left running, and that Disconnect actually stops something. The adapter
/// coming up is proven on a real Windows runner by the client's own test.
/// </summary>
public class WgTunnelSessionTests
{
    private const string ClientPrivate = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private const string ServerPublic = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";

    private static WgParams Params() => new()
    {
        ServerPublicKey = ServerPublic,
        ClientPrivateKey = ClientPrivate,
        AllowedIps = "0.0.0.0/0",
        EndpointPort = 51820,
        Dns = "1.1.1.1",
    };

    private sealed class StubProcess : WgTunnelSession.IProcessHandle
    {
        public readonly List<string> Written = [];
        public Queue<string?> Output = new();
        public bool InputClosed;
        public bool Killed;
        public bool Disposed;
        public bool ExitsOnClose = true;
        private bool _exited;

        public bool HasExited => _exited;
        public void WriteLine(string line) => Written.Add(line);
        public string? ReadLine() => Output.Count > 0 ? Output.Dequeue() : null;
        public void CloseInput()
        {
            InputClosed = true;
            if (ExitsOnClose) _exited = true;
        }
        public bool WaitForExit(TimeSpan timeout) => _exited;
        public void Kill() { Killed = true; _exited = true; }
        public void Dispose() => Disposed = true;
    }

    private sealed class StubHost(StubProcess process) : WgTunnelSession.IProcessHost
    {
        public string? Arguments;
        public int Starts;
        public Exception? ThrowOnStart;

        public WgTunnelSession.IProcessHandle Start(string arguments)
        {
            Starts++;
            Arguments = arguments;
            if (ThrowOnStart is not null) throw ThrowOnStart;
            return process;
        }
    }

    private static StubProcess ReadyProcess()
    {
        var process = new StubProcess();
        process.Output.Enqueue("READY");
        return process;
    }

    [Fact]
    public void RoamTellsTheChildThePhonesNewAddress()
    {
        var process = ReadyProcess();
        var session = new WgTunnelSession(new StubHost(process));
        Assert.True(session.Connect(Params(), "192.168.1.13").Ok);

        Assert.True(session.Roam("192.168.1.14", 51820));

        // One line, after the configuration, in the form the client parses. A
        // phone's address is a DHCP lease, and the PC is the WireGuard
        // initiator — the direction WireGuard's own roaming does not cover.
        Assert.Equal(
            WgTunnelSession.EndpointPrefix + "192.168.1.14:51820", process.Written[^1]);
    }

    [Fact]
    public void RoamDoesNothingWhenNoTunnelIsRunning()
    {
        var session = new WgTunnelSession(new StubHost(ReadyProcess()));

        // Nothing to move, and nothing to write to. Reported rather than thrown:
        // this runs off a beacon, on a timer, and a phone that disconnected a
        // moment ago is ordinary rather than exceptional.
        Assert.False(session.Roam("192.168.1.14", 51820));
    }

    [Fact]
    public void RoamStopsAfterTheTunnelIsDisconnected()
    {
        var process = ReadyProcess();
        var session = new WgTunnelSession(new StubHost(process));
        session.Connect(Params(), "192.168.1.13");
        var beforeDisconnect = process.Written.Count;

        Assert.True(session.Disconnect().Ok);

        Assert.False(session.Roam("192.168.1.14", 51820));
        Assert.Equal(beforeDisconnect, process.Written.Count);
    }

    [Fact]
    public void ConnectHandsTheChildTheIpcConfigurationAndTheTerminator()
    {
        var process = ReadyProcess();
        var session = new WgTunnelSession(new StubHost(process));

        Assert.True(session.Connect(Params(), "192.168.43.1").Ok);

        // The dialect the client's WireGuard actually reads — hex keys, flat
        // key=value — not the readable INI. The phone shipped four releases
        // with that exact confusion.
        Assert.Contains(process.Written, line => line.StartsWith("private_key=", StringComparison.Ordinal));
        Assert.Contains(process.Written, line => line == "endpoint=192.168.43.1:51820");
        Assert.DoesNotContain(process.Written, line => line.Contains("[Interface]", StringComparison.Ordinal));

        // Without the terminator the child waits forever for a configuration it
        // has already been given.
        Assert.Equal(WgTunnelSession.ConfigTerminator, process.Written[^1]);
    }

    [Fact]
    public void TheChildIsToldWhereToRouteAndWhatToCallItself()
    {
        var host = new StubHost(ReadyProcess());
        new WgTunnelSession(host).Connect(Params(), "192.168.43.1");

        Assert.Contains("-address 10.13.37.2/32", host.Arguments);
        Assert.Contains("-routes 0.0.0.0/0", host.Arguments);
        Assert.Contains("-dns 1.1.1.1", host.Arguments);
    }

    [Fact]
    public void ADeclinedElevationPromptSaysSo()
    {
        // "Could not start the tunnel" would send someone looking at their
        // network for something they did on purpose two seconds earlier.
        var host = new StubHost(new StubProcess()) { ThrowOnStart = new WgTunnelSession.ElevationDeclined() };
        var result = new WgTunnelSession(host).Connect(Params(), "192.168.43.1");

        Assert.False(result.Ok);
        Assert.Equal("ERR_WG_ELEVATION_DECLINED", result.ErrorCode);
    }

    [Fact]
    public void APeerThatNeverAnswersIsReportedAsAStaleQrNotAsABrokenTunnel()
    {
        // The failure a person actually hits: the phone mints fresh WireGuard
        // keys every time sharing restarts, so a QR scanned a few minutes ago
        // names keys the endpoint no longer has. The adapter still comes up —
        // nothing about it depends on the peer — so this used to be reported as
        // success, and the app said "Connected (Full Mode)" over a tunnel that
        // could not carry a byte. Rescanning is the fix, so the error has to say
        // that rather than send someone to close their other VPN.
        var process = new StubProcess();
        process.Output.Enqueue(WgTunnelSession.NoHandshakeLine);
        var session = new WgTunnelSession(new StubHost(process));

        var result = session.Connect(Params(), "192.168.43.1");

        Assert.False(result.Ok);
        Assert.Equal("ERR_WG_NO_HANDSHAKE", result.ErrorCode);
        Assert.True(process.InputClosed || process.Killed);
        Assert.False(session.IsRunning);
    }

    [Fact]
    public void AnInstallLocationWindowsWontElevateFromSaysSo()
    {
        // Found on a real machine: %LOCALAPPDATA%\Programs was a junction to
        // another drive, and the elevation broker -- which runs as SYSTEM and
        // resolves the path itself -- returned ERROR_PATH_NOT_FOUND for every
        // executable behind it. No prompt was ever shown, and the app blamed
        // the tunnel: "close any other VPN, then try again". Nothing about that
        // is true or actionable, and someone could close every VPN they own
        // without ever getting closer.
        var host = new StubHost(new StubProcess())
        {
            ThrowOnStart = new WgTunnelSession.ElevationUnavailable(),
        };
        var result = new WgTunnelSession(host).Connect(Params(), "192.168.43.1");

        Assert.False(result.Ok);
        Assert.Equal("ERR_WG_ELEVATION_UNAVAILABLE", result.ErrorCode);
    }

    [Fact]
    public void AChildThatNeverBecomesReadyIsNotLeftRunning()
    {
        // The dangerous shape: a tunnel process alive with an adapter up, while
        // the app believes nothing happened and shows an error.
        var process = new StubProcess(); // no READY, stdout ends
        var host = new StubHost(process);
        var session = new WgTunnelSession(host);

        var result = session.Connect(Params(), "192.168.43.1");

        Assert.False(result.Ok);
        Assert.Equal("ERR_WG_START_FAILED", result.ErrorCode);
        Assert.True(process.InputClosed || process.Killed);
        Assert.False(session.IsRunning);
    }

    [Fact]
    public void AnUnusablePayloadIsRefusedBeforeAnythingIsStarted()
    {
        var host = new StubHost(ReadyProcess());
        var broken = Params() with { AllowedIps = "" };

        var result = new WgTunnelSession(host).Connect(broken, "192.168.43.1");

        Assert.Equal("ERR_QR_INVALID", result.ErrorCode);
        Assert.Equal(0, host.Starts); // nothing was elevated for a QR that cannot work
    }

    [Fact]
    public void DisconnectClosesTheChildAndIsSafeToRepeat()
    {
        var process = ReadyProcess();
        var session = new WgTunnelSession(new StubHost(process));
        session.Connect(Params(), "192.168.43.1");

        Assert.True(session.IsRunning);
        Assert.True(session.Disconnect().Ok);
        Assert.True(process.InputClosed);
        Assert.False(session.IsRunning);

        // Teardown runs on paths that are already unwinding from a failure.
        Assert.True(session.Disconnect().Ok);
    }

    [Fact]
    public void AChildThatWillNotLeaveIsKilled()
    {
        // The adapter goes with the process, so a wedged child is a machine
        // still routing through a tunnel nobody is driving.
        var process = ReadyProcess();
        process.ExitsOnClose = false;
        var session = new WgTunnelSession(new StubHost(process));
        session.Connect(Params(), "192.168.43.1");

        Assert.True(session.Disconnect().Ok);
        Assert.True(process.Killed);
    }

    [Fact]
    public void ASecondConnectDoesNotStartASecondTunnel()
    {
        var host = new StubHost(ReadyProcess());
        var session = new WgTunnelSession(host);
        session.Connect(Params(), "192.168.43.1");

        var second = session.Connect(Params(), "192.168.43.1");

        Assert.False(second.Ok);
        Assert.Equal(1, host.Starts);
    }
}
