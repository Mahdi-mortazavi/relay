using Relay.Core;
using Relay.Core.Proxy;

namespace Relay.App.Services;

/// <summary>
/// Client-side connection driver. States and transitions are the shared
/// machine (/shared/connection-states.json): start → Preparing (apply proxy),
/// ready → Advertising (probing the phone), clientConnected → Connected.
/// Illegal events are dropped by <see cref="Dispatch"/>, mirroring Android.
/// </summary>
public sealed class AppController(IProxyStore proxyStore, IBackupStore backupStore)
{
    public static AppController Instance { get; } =
        new(new WinInetProxyStore(), new FileBackupStore());

    private readonly ProxySession _session = new(proxyStore, backupStore);
    private readonly object _gate = new();

    public string StateName { get; private set; } = ConnectionRules.Initial;
    public QrPayload? Payload { get; private set; }
    public string? ErrorCode { get; private set; }

    /// <summary>Raised on any state change; may fire on a worker thread.</summary>
    public event Action? StateChanged;

    /// <summary>Call once at startup, before any UI: undo a crashed session's proxy.</summary>
    public bool RecoverIfCrashed() => _session.RecoverIfCrashed();

    /// <summary>Full pairing flow from a decoded payload. Runs the blocking parts off the UI thread.</summary>
    public async Task ConnectAsync(QrPayload payload)
    {
        if (payload.Mode != QrPayload.ModeSocks5)
        {
            // Full Mode arrives in Phase 3; a wireguard QR from a newer phone
            // build is a version problem from this client's point of view.
            Fail("ERR_QR_NEWER_VERSION");
            return;
        }
        if (!Dispatch("start", payload)) return;

        var applied = await Task.Run(() => _session.Connect(payload.Host, payload.Port));
        if (!applied.Ok)
        {
            Fail(applied.ErrorCode!);
            return;
        }
        Dispatch("ready");

        var reachable = await Task.Run(() => ProbePhone(payload.Host, payload.Port));
        if (!reachable)
        {
            await Task.Run(_session.Disconnect);
            Fail("ERR_HOST_UNREACHABLE");
            return;
        }
        Dispatch("clientConnected");
    }

    /// <summary>Rollback with verification (AC1.4); state reflects the outcome truthfully.</summary>
    public async Task DisconnectAsync()
    {
        var result = await Task.Run(_session.Disconnect);
        if (result.Ok)
        {
            Dispatch("stop");
        }
        else
        {
            Fail(result.ErrorCode!);
        }
    }

    public void DismissError() => Dispatch("dismiss");

    /// <summary>SOCKS5 handshake probe — proves the phone is reachable and speaking SOCKS.</summary>
    private static bool ProbePhone(string host, int port)
    {
        try
        {
            using var client = new System.Net.Sockets.TcpClient();
            if (!client.ConnectAsync(host, port).Wait(TimeSpan.FromSeconds(5))) return false;
            var stream = client.GetStream();
            stream.WriteTimeout = 3000;
            stream.ReadTimeout = 3000;
            stream.Write([0x05, 0x01, 0x00]); // VER, 1 method, no-auth
            var reply = new byte[2];
            var read = stream.Read(reply, 0, 2);
            return read == 2 && reply[0] == 0x05 && reply[1] == 0x00;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private void Fail(string code)
    {
        lock (_gate)
        {
            // "failure" is legal from Preparing/Advertising/Connected; from Idle
            // (e.g. an invalid scan) we surface the error without a transition
            // by moving through the same table: Idle -> start -> failure.
            if (!ConnectionRules.CanTransition(StateName, "failure"))
            {
                if (ConnectionRules.CanTransition(StateName, "start"))
                {
                    StateName = ConnectionRules.Target(StateName, "start")!;
                }
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
            if (target is "Idle") { Payload = null; ErrorCode = null; }
            if (@event is "dismiss") ErrorCode = null;
        }
        StateChanged?.Invoke();
        return true;
    }
}
