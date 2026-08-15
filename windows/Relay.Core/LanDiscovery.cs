using System.Net;
using System.Net.NetworkInformation;
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

    /// <summary>Digits in a pairing code (/shared/pairing-beacon.md).</summary>
    public const int CodeLength = 2;

    public static readonly TimeSpan Stale = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan ProbeInterval = TimeSpan.FromSeconds(1);

    /// <summary>A phone currently announcing itself.</summary>
    public sealed record Device(string Code, string Mode, string Host, int PortNumber, string? Name, int? PairingPort, DateTimeOffset Seen)
    {
        public string Key => $"{Host}:{PortNumber}";
    }

    private readonly Dictionary<string, Device> _devices = new();
    private readonly object _lock = new();
    private readonly Func<DateTimeOffset> _clock;
    private UdpClient? _socket;
    private CancellationTokenSource? _cancellation;
    private CancellationTokenSource? _probing;

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
        socket.EnableBroadcast = true;
        _socket = socket;
        _cancellation = new CancellationTokenSource();
        _ = ReceiveLoopAsync(socket, _cancellation.Token);
    }

    /// <summary>
    /// Turns the active half of discovery on or off (/shared/pairing-beacon.md
    /// → "The probe"). Off by default: a tray app has no business broadcasting
    /// once a second all day, and this only matters while someone is looking at
    /// the pairing screen.
    ///
    /// Probing is what makes discovery work on a PC nobody configured. Windows
    /// Firewall drops unsolicited inbound UDP to an unelevated app, and Relay's
    /// installer is per-user so it cannot add a rule — pure listening simply
    /// hears nothing, and the user is left with a phone showing a code the PC
    /// says does not exist. A datagram sent first opens the return path.
    /// </summary>
    public void SetProbing(bool on)
    {
        if (on)
        {
            if (_probing is not null) return;
            _probing = new CancellationTokenSource();
            _ = ProbeLoopAsync(_probing.Token);
        }
        else
        {
            _probing?.Cancel();
            _probing?.Dispose();
            _probing = null;
        }
    }

    private async Task ProbeLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            Probe();
            try { await Task.Delay(ProbeInterval, token).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
            // SetProbing(false) cancels and disposes in one step, and this loop
            // can be sitting between the two. Racing the shutdown of the thing
            // that told it to stop is not an error worth propagating.
            catch (ObjectDisposedException) { return; }
        }
    }

    /// <summary>
    /// Sends one probe on every interface. Public so the pairing screen can ask
    /// for one the instant it opens rather than waiting out a tick.
    /// </summary>
    public void Probe()
    {
        var socket = _socket;
        if (socket is null) return;

        var datagram = ProbeDatagram();
        foreach (var address in BroadcastAddresses())
        {
            try
            {
                socket.Send(datagram, datagram.Length, new IPEndPoint(address, Port));
            }
            catch (SocketException)
            {
                // One interface refusing is ordinary — a down VPN adapter, a
                // network the stack is still bringing up. Only every interface
                // failing matters, and that shows as the phone never appearing.
            }
            catch (ObjectDisposedException) { return; }
        }
    }

    /// <summary>The probe datagram from /shared/pairing-beacon.md.</summary>
    public static byte[] ProbeDatagram() =>
        Encoding.UTF8.GetBytes($$"""{"v":{{Version}},"probe":1}""");

    /// <summary>
    /// The broadcast address of each usable IPv4 interface, plus the limited
    /// broadcast address and default gateway addresses.
    ///
    /// Both, deliberately. A directed broadcast is the one that reaches a phone
    /// acting as a hotspot, because that interface is not the PC's default
    /// route; 255.255.255.255 is the one that survives adapters whose netmask
    /// the stack reports oddly. Direct gateway probing covers networks where
    /// Windows Firewall or AP isolation blocks broadcast.
    /// </summary>
    private static IEnumerable<IPAddress> BroadcastAddresses()
    {
        var seen = new HashSet<string> { IPAddress.Broadcast.ToString() };
        yield return IPAddress.Broadcast;

        NetworkInterface[] interfaces;
        try { interfaces = NetworkInterface.GetAllNetworkInterfaces(); }
        catch (NetworkInformationException) { yield break; }

        foreach (var nic in interfaces)
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

            IPInterfaceProperties properties;
            try { properties = nic.GetIPProperties(); }
            catch (NetworkInformationException) { continue; }

            foreach (var unicast in properties.UnicastAddresses)
            {
                var broadcast = BroadcastFor(unicast.Address, unicast.IPv4Mask);
                if (broadcast is not null && seen.Add(broadcast.ToString())) yield return broadcast;
            }

            foreach (var gw in properties.GatewayAddresses)
            {
                if (gw.Address.AddressFamily == AddressFamily.InterNetwork &&
                    !Equals(gw.Address, IPAddress.Any) &&
                    seen.Add(gw.Address.ToString()))
                {
                    yield return gw.Address;
                }
            }
        }
    }

    /// <summary>
    /// The directed broadcast address for an interface: host bits all ones.
    /// Null for anything that is not a usable IPv4 address and mask.
    /// </summary>
    public static IPAddress? BroadcastFor(IPAddress? address, IPAddress? mask)
    {
        if (address is null || mask is null) return null;
        if (address.AddressFamily != AddressFamily.InterNetwork) return null;
        if (mask.AddressFamily != AddressFamily.InterNetwork) return null;

        var host = address.GetAddressBytes();
        var bits = mask.GetAddressBytes();
        // A /32, or an adapter that reports no mask at all, has no broadcast
        // address; sending to the host itself would be a datagram to nowhere.
        if (bits.All(b => b == 0) || bits.All(b => b == 0xFF)) return null;

        var result = new byte[4];
        for (var i = 0; i < 4; i++) result[i] = (byte)(host[i] | (byte)~bits[i]);
        return new IPAddress(result);
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
            if (code is null || code.Length != CodeLength ||
                !code.All(char.IsAsciiDigit) || code[0] == '0') return false;

            var host = root.TryGetProperty("host", out var h) ? h.GetString() : null;
            if (string.IsNullOrWhiteSpace(host) || !IPAddress.TryParse(host, out _)) return false;

            if (!root.TryGetProperty("port", out var p) || p.ValueKind != JsonValueKind.Number) return false;
            var portNumber = p.GetInt32();
            if (portNumber is < 1 or > 65535) return false;

            var mode = root.TryGetProperty("mode", out var m) ? m.GetString() : null;
            if (mode is not ("socks5" or "wireguard")) return false;

            var name = root.TryGetProperty("name", out var n) ? n.GetString() : null;
            if (name is { Length: > 32 }) name = name[..32];

            int? pairingPort = null;
            if (root.TryGetProperty("pairingPort", out var pp) && pp.ValueKind == JsonValueKind.Number)
            {
                var val = pp.GetInt32();
                if (val is >= 1 and <= 65535) pairingPort = val;
            }

            var state = root.TryGetProperty("state", out var s) ? s.GetString() : "sharing";
            stopped = state == "stopped";

            device = new Device(code, mode, host!, portNumber, name, pairingPort, seen);
            return true;
        }
        catch (JsonException)
        {
            return false; // not ours; something else is on this port
        }
    }

    /// <summary>
    /// Records a beacon, and reports a change only when something a person
    /// could see is different.
    ///
    /// A phone beacons once a second forever. Firing the event on every one of
    /// them turned "the set of visible phones changed" into a metronome, and
    /// the pairing screen rebuilt its list — and threw away whatever row had
    /// focus — once a second for as long as it was open.
    /// </summary>
    private void Add(Device device)
    {
        bool changed;
        lock (_lock)
        {
            changed = !_devices.TryGetValue(device.Key, out var known) || !SameToTheUser(known, device);
            _devices[device.Key] = device;
        }
        if (changed) Notify();
    }

    /// <summary>Everything except when it was last heard from.</summary>
    private static bool SameToTheUser(Device a, Device b) =>
        a.Code == b.Code && a.Mode == b.Mode && a.Host == b.Host &&
        a.PortNumber == b.PortNumber && a.Name == b.Name && a.PairingPort == b.PairingPort;

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
        if (digits.Length != CodeLength) return null;
        if (!digits.All(char.IsAsciiDigit)) return null;
        if (digits[0] == '0') return null;
        return digits;
    }

    public void Dispose()
    {
        SetProbing(false);
        _cancellation?.Cancel();
        _socket?.Dispose();
        _socket = null;
        _cancellation?.Dispose();
        _cancellation = null;
    }
}
