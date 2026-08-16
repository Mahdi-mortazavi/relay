using Relay.App.Services;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// The arithmetic behind the numbers on the connected screen.
///
/// Reading the adapter needs an adapter, but every way these figures go wrong
/// is arithmetic: a counter that resets, two samples from the same instant, a
/// window that was hidden for a minute. Those are the cases that put an absurd
/// number in front of someone at the exact moment they are checking whether
/// their connection works, and none of them need a network to reproduce.
/// </summary>
public class TunnelStatsTests
{
    private static TunnelStats.Sample At(long received, long sent, double seconds) =>
        new(received, sent, DateTimeOffset.UnixEpoch.AddSeconds(seconds));

    [Fact]
    public void RateIsBytesOverTheTimeBetweenSamples()
    {
        var (down, up) = TunnelStats.Rate(At(1_000, 500, 0), At(3_000, 1_500, 2));

        Assert.Equal(1_000, down);  // 2000 bytes over 2 seconds
        Assert.Equal(500, up);
    }

    [Fact]
    public void TheFirstSampleHasNothingToCompareAgainst()
    {
        // Zero, not "everything transferred since boot divided by one second",
        // which is what a missing guard here would render on the first tick.
        var (down, up) = TunnelStats.Rate(null, At(9_999_999, 9_999_999, 0));

        Assert.Equal(0, down);
        Assert.Equal(0, up);
    }

    [Fact]
    public void CountersGoingBackwardsReadAsZeroRatherThanAsASpike()
    {
        // The adapter is recreated on reconnect, so its counters restart. Left
        // unhandled this subtracts to a negative and renders as an enormous
        // burst -- precisely when someone is watching to see if reconnecting
        // worked, and precisely the number that would make them think it had.
        var (down, up) = TunnelStats.Rate(At(5_000_000, 5_000_000, 0), At(12, 12, 1));

        Assert.Equal(0, down);
        Assert.Equal(0, up);
    }

    [Fact]
    public void TwoSamplesFromTheSameInstantDoNotDivideByZero()
    {
        var (down, up) = TunnelStats.Rate(At(0, 0, 5), At(1_000, 1_000, 5));

        Assert.Equal(0, down);
        Assert.Equal(0, up);
    }

    [Theory]
    [InlineData(512, "512 B")]
    [InlineData(2048, "2.00 KB")]
    [InlineData(15_728_640, "15.0 MB")]
    [InlineData(1_288_490_188, "1.20 GB")]
    public void BytesAreSizedToFitANarrowPanel(long value, string expected)
        => Assert.Equal(expected, TunnelStats.Bytes(value));

    [Fact]
    public void DurationStaysShortUntilItHasToGrow()
    {
        // Most sessions are minutes, and "00:04:31" spends two characters saying
        // nothing. An hour in, the hours matter and it earns them.
        Assert.Equal("04:31", TunnelStats.Duration(TimeSpan.FromSeconds(271)));
        Assert.Equal("01:42:18", TunnelStats.Duration(new TimeSpan(1, 42, 18)));
    }
}
