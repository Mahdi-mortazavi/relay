using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

public class WgClientConfigTests
{
    // Two real 32-byte keys, base64 as they appear in the QR payload.
    // Fixed byte patterns rather than pretty strings: the first draft used
    // readable text that happened to decode to 30 and 29 bytes, so every test
    // would have been asserting against keys the validator rightly refuses.
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

    [Fact]
    public void Ini_carries_everything_the_tunnel_needs()
    {
        var ini = WgClientConfig.ToIni(Params(), "192.168.43.1");
        Assert.Contains("[Interface]", ini);
        Assert.Contains($"PrivateKey = {ClientPrivate}", ini);
        Assert.Contains("Address = 10.13.37.2/32", ini);
        Assert.Contains("DNS = 1.1.1.1", ini);
        Assert.Contains("[Peer]", ini);
        Assert.Contains($"PublicKey = {ServerPublic}", ini);
        Assert.Contains("AllowedIPs = 0.0.0.0/0", ini);
        Assert.Contains("Endpoint = 192.168.43.1:51820", ini);
    }

    [Fact]
    public void Ini_keeps_the_nat_mapping_alive()
    {
        // Without this the phone's NAT mapping expires while idle and the
        // tunnel goes quiet in one direction with nothing reporting an error.
        Assert.Contains("PersistentKeepalive", WgClientConfig.ToIni(Params(), "192.168.43.1"));
    }

    [Fact]
    public void Ipc_uses_hex_keys_not_base64()
    {
        // The single difference between the two forms, and the one that
        // produces a tunnel which handshakes with nothing and says nothing.
        var ipc = WgClientConfig.ToIpc(Params(), "192.168.43.1");
        Assert.Contains("private_key=" + WgClientConfig.ToHex(ClientPrivate), ipc);
        Assert.Contains("public_key=" + WgClientConfig.ToHex(ServerPublic), ipc);
        Assert.DoesNotContain(ClientPrivate, ipc);
        Assert.DoesNotContain(ServerPublic, ipc);
    }

    [Fact]
    public void Ipc_splits_several_allowed_ranges_into_their_own_lines()
    {
        // The IPC form takes one allowed_ip per line; a comma-joined list is
        // accepted verbatim and then matches nothing.
        var wg = Params() with { AllowedIps = "10.0.0.0/8, 192.168.0.0/16" };
        var ipc = WgClientConfig.ToIpc(wg, "192.168.43.1");
        Assert.Contains("allowed_ip=10.0.0.0/8\n", ipc);
        Assert.Contains("allowed_ip=192.168.0.0/16\n", ipc);
    }

    [Fact]
    public void Hex_conversion_round_trips_a_real_key()
    {
        var hex = WgClientConfig.ToHex(ClientPrivate);
        Assert.Equal(64, hex.Length);
        Assert.Equal(ClientPrivate, Convert.ToBase64String(Convert.FromHexString(hex)));
    }

    [Theory]
    [InlineData("")]
    [InlineData("not base64 at all")]
    [InlineData("dG9vc2hvcnQ=")]  // 8 bytes
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")]  // 45 bytes
    public void A_key_that_is_not_thirty_two_bytes_is_refused(string key)
        => Assert.Throws<ArgumentException>(() => WgClientConfig.ToHex(key));

    [Fact]
    public void An_empty_allowed_ips_is_refused()
    {
        // This is the worst failure to ship: the tunnel comes up, everything
        // looks connected, and not one packet is carried.
        var wg = Params() with { AllowedIps = "" };
        Assert.Throws<ArgumentException>(() => WgClientConfig.ToIni(wg, "192.168.43.1"));
    }

    [Fact]
    public void A_missing_dns_is_refused()
    {
        var wg = Params() with { Dns = "" };
        Assert.Throws<ArgumentException>(() => WgClientConfig.ToIni(wg, "192.168.43.1"));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    [InlineData(70000)]
    public void A_port_outside_the_range_is_refused(int port)
    {
        var wg = Params() with { EndpointPort = port };
        Assert.Throws<ArgumentException>(() => WgClientConfig.ToIni(wg, "192.168.43.1"));
    }

    [Theory]
    [InlineData("")]
    [InlineData("not-an-address")]
    [InlineData("999.1.1.1")]
    public void A_host_that_is_not_an_address_is_refused(string host)
        => Assert.Throws<ArgumentException>(() => WgClientConfig.ToIni(Params(), host));
}
