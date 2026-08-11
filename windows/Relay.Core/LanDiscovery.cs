using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace Relay.Core;

/// <summary>
/// Finds phones announcing themselves on the local network, so a person can
/// pair by typing two digits instead of eight characters.
/// Contract: /shared/pairing-beacon.md — change that first.
/// </summary>
public sealed class LanDiscovery : IDisposable
{
    public const int Port = 47654;
    public const int Version = 1;
    public static readonly TimeSpan Stale = TimeSpan.FromSeconds(5);

    /// <summary>A phone currently announcing itself.</summary>
    public sealed record Device(string Code, string Mode, string Host, int PortNumber, string? Name, DateTimeOffset Seen)
    {
        public string Key => $"{Host}:{PortNumber}";
    }

    private readonly Dictionary<string, Device> _devices = new();
    private readonly object _lock = new();
    private readonly Func<DateTimeOffset> _clock;
    private UdpClient? _socket;
    private CancellationTokenSource? _cancellation;

    public LanDiscovery(Func<DateTimeOffset>? clock = null) => _clock = clock ?? (() => DateTimeOffset.UtcNow);

    /// <summary>Raised whenever the set of visible phones changes.</summary>
    public event Action<IReadOnlyList<Device>>? DevicesChanged;

    public void Start()
    {
        if (_socket is not null) return;

        // ReuseAddress so a second Relay window, or a leftover socket in
        // TIME_WAIT, does not make discovery silently impossible.
        var socket = new UdpClient();
        socket.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        socket.Client.Bind(new IPEndPoint(IPAddress.Any, Port));
        _socket = socket;
        _cancellation = new CancellationTokenSource();
        _ = ReceiveLoopAsync(socket, _cancellation.Token);
    }

    private async Task ReceiveLoopAsync(UdpClient socket, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try
            {
                result = await socket.ReceiveAsync(token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (SocketException) { continue; } // one bad datagram is not the end of discovery

            Observe(result.Buffer);
            Expire();
        }
    }

    /// <summary>
    /// Feeds one datagram in, exactly as the receive loop does. Public because
    /// the interesting behaviour of this class is what it does with the bytes
    /// on the wire, and a test that cannot hand it bytes has to reach through
    /// reflection instead — which tests the reflection, not the parser.
    /// </summary>
    /// <returns>True when the datagram was a beacon this version understands.</returns>
    public bool Observe(byte[] datagram)
    {
        if (!TryParseBeacon(datagram, _clock(), out var device, out var stopped)) return false;
        if (stopped) Remove(device!.Key);
        else Add(device!);
        return true;
    }

    /// <summary>
    /// Parses a beacon. Everything in it is attacker-controlled — anything on
    /// the network can send one — so every field is validated and nothing is
    /// trusted beyond being shown.
    /// </summary>
    public static bool TryParseBeacon(byte[] bytes, DateTimeOffset seen, out Device? device, out bool stopped)
    {
        device = null;
        stopped = false;
        try
        {
            using var json = JsonDocument.Parse(Encoding.UTF8.GetString(bytes));
            var root = json.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return false;
            if (!root.TryGetProperty("v", out var v) || v.ValueKind != JsonValueKind.Number || v.GetInt32() != Version)
                return false;

            var code = root.TryGetProperty("code", out var c) ? c.GetString() : null;
            if (code is null || code.Length != 2 || !code.All(char.IsAsciiDigit) || code[0] == '0') return false;

            var host = root.TryGetProperty("host", out var h) ? h.GetString() : null;
            if (string.IsNullOrWhiteSpace(host) || !IPAddress.TryParse(host, out _)) return false;

            if (!root.TryGetProperty("port", out var p) || p.ValueKind != JsonValueKind.Number) return false;
            var portNumber = p.GetInt32();
            if (portNumber is < 1 or > 65535) return false;

            var mode = root.TryGetProperty("mode", out var m) ? m.GetString() : null;
            if (mode is not ("socks5" or "wireguard")) return false;

            var name = root.TryGetProperty("name", out var n) ? n.GetString() : null;
            if (name is { Length: > 32 }) name = name[..32];

            var state = root.TryGetProperty("state", out var s) ? s.GetString() : "sharing";
            stopped = state == "stopped";

            device = new Device(code, mode, host!, portNumber, name, seen);
            return true;
        }
        catch (JsonException)
        {
            return false; // not ours; something else is on this port
        }
    }

    private void Add(Device device)
    {
        lock (_lock) _devices[device.Key] = device;
        Notify();
    }

    private void Remove(string key)
    {
        bool removed;
        lock (_lock) removed = _devices.Remove(key);
        if (removed) Notify();
    }

    internal void Expire()
    {
        var now = _clock();
        bool changed;
        lock (_lock)
        {
            var dead = _devices.Where(kv => now - kv.Value.Seen > Stale).Select(kv => kv.Key).ToList();
            foreach (var key in dead) _devices.Remove(key);
            changed = dead.Count > 0;
        }
        if (changed) Notify();
    }

    private void Notify() => DevicesChanged?.Invoke(Devices);

    public IReadOnlyList<Device> Devices
    {
        get { lock (_lock) return _devices.Values.OrderBy(d => d.Code).ToList(); }
    }

    /// <summary>
    /// Phones currently answering to <paramref name="code"/>. More than one is
    /// possible for a moment when a phone joins the network late, which is why
    /// this returns a list rather than picking for the caller.
    /// </summary>
    public IReadOnlyList<Device> Match(string? code)
    {
        Expire();
        var normalized = NormalizeCode(code);
        if (normalized is null) return [];
        lock (_lock) return _devices.Values.Where(d => d.Code == normalized).ToList();
    }

    /// <summary>
    /// The one place a typed code becomes a comparable string. Both platforms
    /// must agree exactly: a box that accepts what the matcher rejects is a bug
    /// this project has already shipped once.
    /// </summary>
    public static string? NormalizeCode(string? input)
    {
        if (input is null) return null;
        var digits = new string(input.Where(ch => !char.IsWhiteSpace(ch)).ToArray());
        if (digits.Length != 2) return null;
        if (!digits.All(char.IsAsciiDigit)) return null;
        if (digits[0] == '0') return null;
        return digits;
    }

    public void Dispose()
    {
        _cancellation?.Cancel();
        _socket?.Dispose();
        _socket = null;
        _cancellation?.Dispose();
        _cancellation = null;
    }
}
