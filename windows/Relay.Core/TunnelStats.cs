using System.Net.NetworkInformation;

namespace Relay.Core;

/// <summary>
/// What the tunnel is actually carrying, read from the adapter Windows created.
///
/// Every number here comes from the operating system's own counters for the
/// Relay interface, so it cannot drift from reality: if the figure moves, bytes
/// moved. Nothing is estimated, and nothing is remembered across a session --
/// a fresh tunnel starts from zero because the adapter it reads did too.
///
/// The arithmetic is separated from the reading on purpose. Rates are the part
/// that is easy to get quietly wrong -- a counter that resets, two samples from
/// the same instant, a paused UI producing a minute-wide gap -- and all of that
/// is testable without a network adapter in the room.
///
/// Lives in Relay.Core rather than beside the window for the reason
/// WgTunnelSession does: free of any UI type, so the suite can reach it.
/// </summary>
public sealed class TunnelStats(string adapterName = TunnelStats.DefaultAdapter)
{
    /// <summary>The adapter the tunnel process creates (see /wg/cmd/relaywg-client).</summary>
    public const string DefaultAdapter = "Relay";

    /// <summary>One reading of the counters, with the moment it was taken.</summary>
    public readonly record struct Sample(long Received, long Sent, DateTimeOffset At);

    /// <summary>What the UI shows. Rates are bytes per second.</summary>
    public readonly record struct Reading(
        long Received,
        long Sent,
        double DownPerSecond,
        double UpPerSecond,
        TimeSpan Duration,
        TimeSpan? Latency);

    private Sample? _previous;
    private DateTimeOffset? _since;

    /// <summary>Starts the clock. Called when the tunnel is confirmed up.</summary>
    public void Begin(DateTimeOffset now)
    {
        _since = now;
        _previous = null;
    }

    public void Reset()
    {
        _since = null;
        _previous = null;
    }

    /// <summary>
    /// Reads the adapter, or null when it is not there — which is the ordinary
    /// state for most of the app's life and not worth an exception.
    /// </summary>
    public Sample? Read(DateTimeOffset now)
    {
        try
        {
            var nic = NetworkInterface.GetAllNetworkInterfaces()
                .FirstOrDefault(n => n.Name.Equals(adapterName, StringComparison.OrdinalIgnoreCase));
            if (nic is null || nic.OperationalStatus != OperationalStatus.Up) return null;
            var stats = nic.GetIPStatistics();
            return new Sample(stats.BytesReceived, stats.BytesSent, now);
        }
        catch (NetworkInformationException)
        {
            // The adapter can vanish between the enumeration and the read; that
            // is a disconnect in progress, not a fault worth surfacing.
            return null;
        }
    }

    /// <summary>Folds a new sample in and produces what the UI should display.</summary>
    public Reading? Update(DateTimeOffset now, TimeSpan? latency)
    {
        var current = Read(now);
        if (current is null) return null;

        var (down, up) = Rate(_previous, current.Value);
        _previous = current;

        return new Reading(
            current.Value.Received,
            current.Value.Sent,
            down,
            up,
            _since is null ? TimeSpan.Zero : now - _since.Value,
            latency);
    }

    /// <summary>
    /// Bytes per second between two samples.
    ///
    /// Returns zero rather than a wrong number in every case where the honest
    /// answer is unknown: no previous sample, no time between them, or counters
    /// that went backwards — which happens when the adapter is recreated, and
    /// would otherwise render as an enormous spike at the exact moment the user
    /// is looking to see whether reconnecting worked.
    /// </summary>
    public static (double Down, double Up) Rate(Sample? previous, Sample current)
    {
        if (previous is null) return (0, 0);
        var elapsed = (current.At - previous.Value.At).TotalSeconds;
        if (elapsed <= 0) return (0, 0);

        var down = current.Received - previous.Value.Received;
        var up = current.Sent - previous.Value.Sent;
        if (down < 0 || up < 0) return (0, 0);

        return (down / elapsed, up / elapsed);
    }

    /// <summary>
    /// Round-trip time to the other end of the tunnel, or null if it did not
    /// answer.
    ///
    /// Deliberately the peer's tunnel address and not a public host: this
    /// measures the link Relay is responsible for. Calling the latency to a
    /// website "VPN latency" would blame Relay for the rest of the internet.
    /// </summary>
    public static TimeSpan? PingPeer(string peerAddress, int timeoutMs = 1500)
    {
        try
        {
            using var ping = new Ping();
            var reply = ping.Send(peerAddress, timeoutMs);
            return reply?.Status == IPStatus.Success
                ? TimeSpan.FromMilliseconds(reply.RoundtripTime)
                : null;
        }
        catch (Exception)
        {
            // Ping is a nicety; a tunnel that carries traffic but drops ICMP is
            // still a working tunnel, so this never fails the connection.
            return null;
        }
    }

    /// <summary>"1.2 MB", "340 KB" — sized for a panel this narrow.</summary>
    public static string Bytes(long value)
    {
        if (value < 1024) return $"{value} B";
        double v = value;
        string[] units = ["KB", "MB", "GB", "TB"];
        foreach (var unit in units)
        {
            v /= 1024;
            if (v < 1024 || unit == "TB")
                return v >= 100 ? $"{v:0} {unit}" : v >= 10 ? $"{v:0.0} {unit}" : $"{v:0.00} {unit}";
        }
        return $"{value} B";
    }

    /// <summary>"2.4 MB/s".</summary>
    public static string Rate(double bytesPerSecond) => $"{Bytes((long)bytesPerSecond)}/s";

    /// <summary>"01:42:18", or "04:31" under an hour — the common case stays short.</summary>
    public static string Duration(TimeSpan value) =>
        value.TotalHours >= 1
            ? $"{(int)value.TotalHours:00}:{value.Minutes:00}:{value.Seconds:00}"
            : $"{value.Minutes:00}:{value.Seconds:00}";
}
