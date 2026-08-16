using System.Net.Sockets;
using Relay.Core;
using Relay.Core.Net;
using Relay.Core.Proxy;

namespace Relay.App.Services;

/// <summary>
/// Client-side connection driver. States and transitions are the shared machine
/// (/shared/connection-states.json). "Reconnecting" is an in-place presentation
/// annotation, not a state transition (ADR-0007): a brief drop keeps the proxy
/// applied and retries on the bounded schedule; only exhaustion rolls back and
/// surfaces ERR_CONNECTION_LOST.
/// </summary>
public sealed class AppController(IProxyStore proxyStore, IBackupStore backupStore)
{
    public static AppController Instance { get; } =
        new(new WinInetProxyStore(), new FileBackupStore());

    private readonly ProxySession _session = new(proxyStore, backupStore);

    /// <summary>
    /// Full Mode's tunnel. The client sits beside Relay.exe, with wintun.dll
    /// next to it — the DLL is loaded from the executable's own directory, so
    /// the two travel together or neither works.
    /// </summary>
    private readonly WgTunnelSession _tunnel = new(new ElevatedTunnelHost(
        Path.Combine(AppContext.BaseDirectory, "relaywg-client.exe")));
    private readonly object _gate = new();
    private readonly object _sessionLock = new(); // serializes all _session IO
    private CancellationTokenSource? _supervisor;

    public string StateName { get; private set; } = ConnectionRules.Initial;
    public QrPayload? Payload { get; private set; }
    public string? ErrorCode { get; private set; }
    public bool Reconnecting { get; private set; }

    /// <summary>Raised on any state change; may fire on a worker thread.</summary>
    public event Action? StateChanged;

    private enum Probe { Ok, Refused, Unreachable }

    /// <summary>Call once at startup, before any UI: undo a crashed session's proxy.</summary>
    public bool RecoverIfCrashed()
    {
        var recovered = _session.RecoverIfCrashed();
        if (recovered) LocalLog.Add("Recovered proxy from a previous crash");
        ShutdownRecoveryHook.Disarm();
        return recovered;
    }

    /// <summary>Full pairing flow from a decoded payload. Runs the blocking parts off the UI thread.</summary>
    public async Task ConnectAsync(QrPayload payload)
    {
        if (payload.Mode == QrPayload.ModeWireguard)
        {
            await ConnectFullModeAsync(payload);
            return;
        }
        if (payload.Mode != QrPayload.ModeSocks5)
        {
            Fail("ERR_QR_NEWER_VERSION");
            return;
        }
        if (!Dispatch("start", payload)) return;
        LocalLog.Add($"Connecting to {payload.Host}:{payload.Port}");

        // Actionable before we touch the system: are we even on the phone's network?
        // Enumerating adapters can throw on machines with flaky VPN/TAP or
        // Hyper-V adapters, and this runs under an async void event handler —
        // an escaping exception would take the whole app down on Connect.
        bool onLocalSubnet;
        try
        {
            onLocalSubnet = await Task.Run(() => NetworkCheck.IsHostOnLocalSubnet(payload.Host));
        }
        catch (Exception ex)
        {
            // Undecidable is not the same as wrong: nothing has been touched yet,
            // so let the connection attempt proceed and let the probe judge it.
            LocalLog.Add($"Subnet check failed ({ex.GetType().Name}); continuing");
            onLocalSubnet = true;
        }
        if (!onLocalSubnet)
        {
            LocalLog.Add("Host is not on any local subnet");
            Fail("ERR_WRONG_NETWORK");
            return;
        }

        ProxySession.Result applied;
        try
        {
            applied = await ConnectLocked(payload.Host, payload.Port);
        }
        catch (Exception ex)
        {
            // A registry/file hiccup during apply must not crash the app; undo and surface.
            LocalLog.Add($"Proxy apply threw: {ex.Message}");
            try { await DisconnectLocked(); } catch { }
            Fail("ERR_PROXY_APPLY_FAILED");
            return;
        }
        if (!applied.Ok)
        {
            LocalLog.Add($"Proxy apply failed: {applied.ErrorCode}");
            Fail(applied.ErrorCode!);
            return;
        }
        // A concurrent Disconnect may have moved us back to Idle after the proxy
        // was applied; if so, undo it rather than leaving the proxy on with an
        // Idle UI (silent, unrecoverable-from-the-window proxy leak).
        if (!Dispatch("ready"))
        {
            try { await DisconnectLocked(); } catch { }
            return;
        }

        var probe = await Task.Run(() => ProbePhone(payload.Host, payload.Port));
        if (probe != Probe.Ok)
        {
            try { await DisconnectLocked(); } catch { } // roll back before surfacing
            var code = probe == Probe.Refused ? "ERR_FIREWALL_BLOCKED" : "ERR_HOST_UNREACHABLE";
            LocalLog.Add($"Initial probe failed → {code}");
            Fail(code);
            return;
        }
        if (!Dispatch("clientConnected"))
        {
            try { await DisconnectLocked(); } catch { }
            return;
        }
        LocalLog.Add("Connected");
        // From here until a clean disconnect, an abrupt end (sign-out, shutdown,
        // taskkill, a killed upgrade) would strand the system proxy. Arm the
        // recovery hook so the next sign-in undoes it even if this process never
        // runs its own cleanup.
        ShutdownRecoveryHook.Arm();
        StartSupervisor(payload);
    }

    /// <summary>Rollback with verification (AC1.4); state reflects the outcome truthfully.</summary>
    public async Task DisconnectAsync()
    {
        StopSupervisor();

        // Full Mode first: if a tunnel is up, that is what "connected" meant,
        // and no proxy was ever applied to roll back.
        if (_tunnel.IsRunning)
        {
            WgTunnelSession.Result stopped;
            try
            {
                stopped = await TunnelDisconnectLocked();
            }
            catch (Exception ex)
            {
                LocalLog.Add($"Tunnel stop threw: {ex.Message}");
                Fail("ERR_WG_STOP_FAILED");
                return;
            }
            if (stopped.Ok)
            {
                LocalLog.Add("Disconnected (Full Mode)");
                if (!Dispatch("stop")) Dispatch("dismiss");
            }
            else
            {
                LocalLog.Add($"Tunnel would not stop: {stopped.ErrorCode}");
                Fail(stopped.ErrorCode!);
            }
            return;
        }

        ProxySession.Result result;
        try
        {
            result = await DisconnectLocked();
        }
        catch (Exception ex)
        {
            LocalLog.Add($"Disconnect threw: {ex.Message}");
            Fail("ERR_ROLLBACK_INCOMPLETE");
            return;
        }
        if (result.Ok)
        {
            ShutdownRecoveryHook.Disarm();
            LocalLog.Add("Disconnected");
            // "stop" is legal from Connected; a retry from the Error surface
            // (ERR_ROLLBACK_INCOMPLETE) clears via "dismiss" instead.
            if (!Dispatch("stop")) Dispatch("dismiss");
        }
        else
        {
            LocalLog.Add($"Rollback incomplete: {result.ErrorCode}");
            Fail(result.ErrorCode!);
        }
    }

    /// <summary>
    /// Full Mode (ADR-0008): a WireGuard tunnel to the phone instead of a
    /// system proxy.
    ///
    /// Shorter than the Fast Mode path above, and the difference is real rather
    /// than cosmetic. Relay changes nothing on this machine here — the adapter
    /// and its routes belong to the tunnel process and vanish with it — so
    /// there is no snapshot to take, no rollback to verify, and no recovery
    /// hook to arm against a crash.
    ///
    /// There is no probe because the tunnel reports ready only after a real
    /// WireGuard handshake, which is a stronger statement than "something
    /// answered on that port". That was written before it was true: the client
    /// used to print READY as soon as the adapter existed, so this said
    /// "Connected (Full Mode)" over a tunnel whose peer had never answered, and
    /// — with no probe and no supervision, on the strength of this very comment
    /// — went on saying it. The client now waits for the handshake, and
    /// <see cref="StartTunnelWatch"/> keeps watching afterwards.
    /// </summary>
    private async Task ConnectFullModeAsync(QrPayload payload)
    {
        if (payload.Wg is null)
        {
            // The decoder rejects this already; belt and braces, because the
            // alternative is a NullReferenceException inside the tunnel.
            Fail("ERR_QR_INVALID");
            return;
        }
        if (!Dispatch("start", payload)) return;
        LocalLog.Add($"Full Mode: dialling {payload.Host}:{payload.Port}");

        WgTunnelSession.Result started;
        try
        {
            started = await TunnelConnectLocked(payload.Wg, payload.Host);
        }
        catch (Exception ex)
        {
            LocalLog.Add($"Tunnel start threw: {ex.Message}");
            Fail("ERR_WG_START_FAILED");
            return;
        }
        if (!started.Ok)
        {
            LocalLog.Add($"Tunnel did not start: {started.ErrorCode}");
            Fail(started.ErrorCode!);
            return;
        }

        // A concurrent Disconnect may have moved us back to Idle while the
        // adapter was coming up. Leaving it running would route the machine
        // through a tunnel the UI says nothing about.
        if (!Dispatch("ready"))
        {
            try { await TunnelDisconnectLocked(); } catch { }
            return;
        }
        // And then say so. The state machine's Connected is what the whole UI is
        // a projection of, and Full Mode stopped one transition short of it --
        // "ready" only reaches Advertising. So the tunnel came up, carried real
        // traffic, and the window sat on "Connecting…" with a Cancel button for
        // as long as you cared to look at it.
        //
        // It survived because the log line below says "Connected" and that is
        // what everyone read, while the screen -- which has no UI automation on
        // any runner, and needs a real elevation prompt to reach at all -- said
        // something else entirely.
        //
        // One client, always: the tunnel has exactly one peer (ADR-0009).
        if (!Dispatch("clientConnected"))
        {
            try { await TunnelDisconnectLocked(); } catch { }
            return;
        }
        LocalLog.Add("Connected (Full Mode)");
        StartTunnelWatch();
    }

    /// <summary>
    /// Full Mode's liveness, which nothing used to check.
    ///
    /// The tunnel process exits when its peer stops handshaking, and the adapter
    /// goes with it, so the process being gone *is* "the tunnel is dead" — one
    /// signal rather than two that can disagree. No reconnect schedule here, and
    /// that is deliberate: the phone mints fresh keys every time sharing
    /// restarts, so the configuration this session was built from cannot be
    /// dialled again. Re-pairing is the only honest recovery, and saying so
    /// beats retrying five times against keys that no longer exist.
    /// </summary>
    private void StartTunnelWatch()
    {
        StopSupervisor();
        var watch = new CancellationTokenSource();
        _supervisor = watch;
        _ = Task.Run(async () =>
        {
            try
            {
                while (!watch.IsCancellationRequested)
                {
                    await Task.Delay(TunnelWatchInterval, watch.Token).ConfigureAwait(false);
                    if (_tunnel.IsRunning) continue;
                    LocalLog.Add("The tunnel stopped — the phone is no longer answering");
                    Fail("ERR_CONNECTION_LOST");
                    return;
                }
            }
            catch (OperationCanceledException)
            {
                // Disconnect cancelled us; that is the ordinary way out.
            }
        }, watch.Token);
    }

    /// <summary>How often the tunnel is checked for having gone.</summary>
    private static readonly TimeSpan TunnelWatchInterval = TimeSpan.FromSeconds(5);

    public void DismissError() => Dispatch("dismiss");

    // All ProxySession IO goes through these so a user Disconnect and the
    // reconnect supervisor can never touch the registry/backup concurrently.
    private Task<ProxySession.Result> ConnectLocked(string host, int port) =>
        Task.Run(() => { lock (_sessionLock) return _session.Connect(host, port); });

    private Task<ProxySession.Result> DisconnectLocked() =>
        Task.Run(() => { lock (_sessionLock) return _session.Disconnect(); });

    // Full Mode's tunnel shares the same lock: a user Disconnect and anything
    // else touching the session must not overlap, exactly as for the proxy.
    private Task<WgTunnelSession.Result> TunnelConnectLocked(WgParams wg, string host) =>
        Task.Run(() => { lock (_sessionLock) return _tunnel.Connect(wg, host); });

    private Task<WgTunnelSession.Result> TunnelDisconnectLocked() =>
        Task.Run(() => { lock (_sessionLock) return _tunnel.Disconnect(); });

    // --- reconnect supervisor (ADR-0007) -------------------------------------

    private void StartSupervisor(QrPayload payload)
    {
        StopSupervisor();
        var cts = new CancellationTokenSource();
        lock (_gate) { _supervisor = cts; }
        _ = SuperviseAsync(payload, cts.Token);
    }

    private void StopSupervisor()
    {
        // Swap the field out under the lock and operate on the local so a
        // concurrent Start/Stop can't Cancel-after-Dispose or double-dispose.
        CancellationTokenSource? cts;
        lock (_gate) { cts = _supervisor; _supervisor = null; }
        if (cts is null) return;
        try { cts.Cancel(); } catch { }
        cts.Dispose();
    }

    private async Task SuperviseAsync(QrPayload payload, CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(SupervisePollMs, token);
                if (StateName != "Connected") return;
                if (await Task.Run(() => ProbePhone(payload.Host, payload.Port)) == Probe.Ok) continue;

                // Brief drop: keep the proxy applied and retry on the bounded schedule.
                LocalLog.Add("Connection lost — reconnecting");
                SetReconnecting(true);
                var recovered = false;
                for (var i = 0; i < ReconnectPolicy.AttemptDelaysMs.Count; i++)
                {
                    await Task.Delay(ReconnectPolicy.AttemptDelaysMs[i], token);
                    if (await Task.Run(() => ProbePhone(payload.Host, payload.Port)) == Probe.Ok)
                    {
                        LocalLog.Add($"Recovered after attempt {i + 1}");
                        recovered = true;
                        break;
                    }
                }
                SetReconnecting(false);
                if (!recovered)
                {
                    // If the user already asked to disconnect, that path owns teardown.
                    if (token.IsCancellationRequested) return;
                    LocalLog.Add("Reconnect budget exhausted");
                    ProxySession.Result rollback;
                    try
                    {
                        rollback = await DisconnectLocked(); // now roll back
                    }
                    catch (Exception ex)
                    {
                        // This is a fire-and-forget task: an exception here used to
                        // vanish as an unobserved task fault, leaving the proxy
                        // applied, the phone gone and the UI still saying
                        // "Connected" — the app lying about the state of the
                        // machine. Surface it as the actionable error instead.
                        LocalLog.Add($"Rollback after connection loss threw: {ex.Message}");
                        Fail("ERR_ROLLBACK_INCOMPLETE");
                        return;
                    }
                    Fail(rollback.Ok ? "ERR_CONNECTION_LOST" : rollback.ErrorCode!);
                    if (rollback.Ok) ShutdownRecoveryHook.Disarm();
                    return;
                }
            }
        }
        catch (OperationCanceledException)
        {
            // Normal on Disconnect/Exit.
        }
        catch (Exception ex)
        {
            // Nothing else observes this task, so an escape would be silent.
            LocalLog.Add($"Reconnect supervisor failed: {ex.Message}");
            Fail("ERR_CONNECTION_LOST");
        }
    }

    /// <summary>SOCKS5 handshake probe; distinguishes an actively refused port from an unreachable host.</summary>
    private static Probe ProbePhone(string host, int port)
    {
        try
        {
            using var client = new TcpClient();
            if (!client.ConnectAsync(host, port).Wait(TimeSpan.FromSeconds(5)))
            {
                return Probe.Unreachable; // timed out — not on the network / phone gone
            }
            var stream = client.GetStream();
            stream.WriteTimeout = 3000;
            stream.ReadTimeout = 3000;
            stream.Write([0x05, 0x01, 0x00]); // VER, 1 method, no-auth
            var reply = new byte[2];
            var read = stream.Read(reply, 0, 2);
            return read == 2 && reply[0] == 0x05 && reply[1] == 0x00 ? Probe.Ok : Probe.Unreachable;
        }
        catch (AggregateException ae) when (ae.InnerException is SocketException se)
        {
            return se.SocketErrorCode == SocketError.ConnectionRefused ? Probe.Refused : Probe.Unreachable;
        }
        catch (SocketException se)
        {
            return se.SocketErrorCode == SocketError.ConnectionRefused ? Probe.Refused : Probe.Unreachable;
        }
        catch (Exception)
        {
            return Probe.Unreachable;
        }
    }

    private void SetReconnecting(bool active)
    {
        lock (_gate) Reconnecting = active;
        StateChanged?.Invoke();
    }

    private void Fail(string code)
    {
        lock (_gate)
        {
            Reconnecting = false;
            // "failure" is legal from Preparing/Advertising/Connected; from Idle
            // (e.g. an invalid scan) move Idle -> start first so the error shows.
            if (!ConnectionRules.CanTransition(StateName, "failure") &&
                ConnectionRules.CanTransition(StateName, "start"))
            {
                StateName = ConnectionRules.Target(StateName, "start")!;
            }
            if (ConnectionRules.CanTransition(StateName, "failure"))
            {
                StateName = ConnectionRules.Target(StateName, "failure")!;
                ErrorCode = code;
            }
        }
        StateChanged?.Invoke();
    }

    private bool Dispatch(string @event, QrPayload? payload = null)
    {
        lock (_gate)
        {
            var target = ConnectionRules.Target(StateName, @event);
            if (target is null) return false;
            StateName = target;
            if (payload is not null) Payload = payload;
            if (target is "Idle") { Payload = null; ErrorCode = null; Reconnecting = false; }
            if (@event is "dismiss") ErrorCode = null;
        }
        StateChanged?.Invoke();
        return true;
    }

    private const int SupervisePollMs = 4000;
}
