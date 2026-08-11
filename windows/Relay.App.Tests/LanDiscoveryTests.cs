using System.Text;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

public class LanDiscoveryTests
{
    private static byte[] Beacon(string json) => Encoding.UTF8.GetBytes(json);

    private const string Good =
        """{"v":1,"code":"42","mode":"socks5","host":"192.168.43.1","port":1080,"name":"Pixel 4a","state":"sharing"}""";

    [Fact]
    public void Parses_a_well_formed_beacon()
    {
        using var discovery = new LanDiscovery();
        Assert.True(discovery.TryParse(Beacon(Good), out var device, out var stopped));
        Assert.False(stopped);
        Assert.Equal("42", device!.Code);
        Assert.Equal("192.168.43.1", device.Host);
        Assert.Equal(1080, device.PortNumber);
        Assert.Equal("Pixel 4a", device.Name);
        Assert.Equal("socks5", device.Mode);
    }

    [Fact]
    public void Reports_a_stopped_beacon_as_stopped()
    {
        using var discovery = new LanDiscovery();
        var json = Good.Replace("\"sharing\"", "\"stopped\"");
        Assert.True(discovery.TryParse(Beacon(json), out _, out var stopped));
        Assert.True(stopped);
    }

    // Everything on the network can send one of these, so the parser is the
    // trust boundary. Each of these is a datagram a hostile or broken sender
    // could produce, and none of them may become a device the user can pick.
    [Theory]
    [InlineData("not json at all")]
    [InlineData("")]
    [InlineData("[]")]
    [InlineData("""{"v":2,"code":"42","mode":"socks5","host":"192.168.1.1","port":1080}""")]
    [InlineData("""{"code":"42","mode":"socks5","host":"192.168.1.1","port":1080}""")]
    [InlineData("""{"v":1,"code":"4","mode":"socks5","host":"192.168.1.1","port":1080}""")]
    [InlineData("""{"v":1,"code":"420","mode":"socks5","host":"192.168.1.1","port":1080}""")]
    [InlineData("""{"v":1,"code":"04","mode":"socks5","host":"192.168.1.1","port":1080}""")]
    [InlineData("""{"v":1,"code":"4a","mode":"socks5","host":"192.168.1.1","port":1080}""")]
    [InlineData("""{"v":1,"code":"42","mode":"socks5","host":"not-an-ip","port":1080}""")]
    [InlineData("""{"v":1,"code":"42","mode":"socks5","host":"192.168.1.1","port":0}""")]
    [InlineData("""{"v":1,"code":"42","mode":"socks5","host":"192.168.1.1","port":70000}""")]
    [InlineData("""{"v":1,"code":"42","mode":"telnet","host":"192.168.1.1","port":1080}""")]
    [InlineData("""{"v":1,"code":"42","mode":"socks5","port":1080}""")]
    public void Rejects_malformed_beacons(string json)
    {
        using var discovery = new LanDiscovery();
        Assert.False(discovery.TryParse(Beacon(json), out _, out _));
    }

    [Fact]
    public void Truncates_an_overlong_device_name()
    {
        // The name is displayed, and it comes from the network. A sender that
        // pads it to a kilobyte must not get a kilobyte onto the screen.
        using var discovery = new LanDiscovery();
        var json = Good.Replace("Pixel 4a", new string('x', 500));
        Assert.True(discovery.TryParse(Beacon(json), out var device, out _));
        Assert.Equal(32, device!.Name!.Length);
    }

    [Fact]
    public void Match_finds_the_phone_with_that_code()
    {
        var now = DateTimeOffset.UtcNow;
        using var discovery = new LanDiscovery(() => now);
        Assert.True(discovery.TryParse(Beacon(Good), out var device, out _));
        Add(discovery, device!);

        var found = discovery.Match("42");
        Assert.Single(found);
        Assert.Equal("192.168.43.1", found[0].Host);
    }

    [Fact]
    public void Match_returns_nothing_for_an_unknown_code()
    {
        var now = DateTimeOffset.UtcNow;
        using var discovery = new LanDiscovery(() => now);
        Assert.True(discovery.TryParse(Beacon(Good), out var device, out _));
        Add(discovery, device!);
        Assert.Empty(discovery.Match("77"));
    }

    [Fact]
    public void Match_returns_both_phones_when_two_answer_to_one_code()
    {
        // Rare but real: a phone that joined late can collide for a second. The
        // caller has to ask which one, so it must see both rather than a
        // silently-picked first.
        var now = DateTimeOffset.UtcNow;
        using var discovery = new LanDiscovery(() => now);
        Assert.True(discovery.TryParse(Beacon(Good), out var first, out _));
        Assert.True(discovery.TryParse(
            Beacon(Good.Replace("192.168.43.1", "192.168.43.9")), out var second, out _));
        Add(discovery, first!);
        Add(discovery, second!);

        Assert.Equal(2, discovery.Match("42").Count);
    }

    [Fact]
    public void A_phone_that_stopped_announcing_expires()
    {
        var now = DateTimeOffset.UtcNow;
        var clock = now;
        using var discovery = new LanDiscovery(() => clock);
        Assert.True(discovery.TryParse(Beacon(Good), out var device, out _));
        Add(discovery, device!);
        Assert.Single(discovery.Match("42"));

        // Past the staleness window: the phone left, or the network dropped.
        clock = now + LanDiscovery.Stale + TimeSpan.FromSeconds(1);
        Assert.Empty(discovery.Match("42"));
    }

    [Theory]
    [InlineData("42", "42")]
    [InlineData(" 42 ", "42")]
    [InlineData("4 2", "42")]
    [InlineData("99", "99")]
    [InlineData("10", "10")]
    [InlineData("4", null)]
    [InlineData("421", null)]
    [InlineData("04", null)]
    [InlineData("4a", null)]
    [InlineData("4-2", null)]
    [InlineData("", null)]
    [InlineData(null, null)]
    public void NormalizeCode_matches_the_contract(string? input, string? expected)
        => Assert.Equal(expected, LanDiscovery.NormalizeCode(input));

    /// <summary>
    /// Feeds a parsed device in without a socket. Discovery's own Add is
    /// private because nothing outside the receive loop should invent devices;
    /// the tests reach it through the same parse the loop uses.
    /// </summary>
    private static void Add(LanDiscovery discovery, LanDiscovery.Device device)
    {
        var field = typeof(LanDiscovery).GetField("_devices",
            System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)!;
        var map = (Dictionary<string, LanDiscovery.Device>)field.GetValue(discovery)!;
        map[device.Key] = device;
    }
}
