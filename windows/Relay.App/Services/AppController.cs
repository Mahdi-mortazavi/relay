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
        return recovered;
    }

    /// <summary>Full pairing flow from a decoded payload. Runs the blocking parts off the UI thread.</summary>
    public async Task ConnectAsync(QrPayload payload)
    {
        if (payload.Mode != QrPayload.ModeSocks5)
        {
            Fail("ERR_QR_NEWER_VERSION");
            return;
        }
        if (!Dispatch("start", payload)) return;
        LocalLog.Add($"Connecting to {payload.Host}:{payload.Port}");

        // Actionable before we touch the system: are we even on the phone's network?
        if (!await Task.Run(() => NetworkCheck.IsHostOnLocalSubnet(payload.Host)))
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
        Dispatch("ready");

        var probe = await Task.Run(() => ProbePhone(payload.Host, payload.Port));
        if (probe != Probe.Ok)
        {
            try { await DisconnectLocked(); } catch { } // roll back before surfacing
            var code = probe == Probe.Refused ? "ERR_FIREWALL_BLOCKED" : "ERR_HOST_UNREACHABLE";
            LocalLog.Add($"Initial probe failed → {code}");
            Fail(code);
            return;
        }
        LocalLog.Add("Connected");
        Dispatch("clientConnected");
        StartSupervisor(payload);
    }

    /// <summary>Rollback with verification (AC1.4); state reflects the outcome truthfully.</summary>
    public async Task DisconnectAsync()
    {
        StopSupervisor();
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
            LocalLog.Add("Disconnected");
            Dispatch("stop");
        }
        else
        {
            LocalLog.Add($"Rollback incomplete: {result.ErrorCode}");
            Fail(result.ErrorCode!);
        }
    }

    public void DismissError() => Dispatch("dismiss");

    // All ProxySession IO goes through these so a user Disconnect and the
    // reconnect supervisor can never touch the registry/backup concurrently.
    private Task<ProxySession.Result> ConnectLocked(string host, int port) =>
        Task.Run(() => { lock (_sessionLock) return _session.Connect(host, port); });

    private Task<ProxySession.Result> DisconnectLocked() =>
        Task.Run(() => { lock (_sessionLock) return _session.Disconnect(); });

    // --- reconnect supervisor (ADR-0007) -------------------------------------

    private void StartSupervisor(QrPayload payload)
    {
        StopSupervisor();
        var cts = new CancellationTokenSource();
        _supervisor = cts;
        _ = SuperviseAsync(payload, cts.Token);
    }

    private void StopSupervisor()
    {
        _supervisor?.Cancel();
        _supervisor?.Dispose();
        _supervisor = null;
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
                    await DisconnectLocked(); // now roll back
                    Fail("ERR_CONNECTION_LOST");
                    return;
                }
            }
        }
        catch (OperationCanceledException)
        {
            // Normal on Disconnect/Exit.
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
