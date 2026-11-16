using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

public class ClientPreferencesTests
{
    [Fact]
    public void SaveAndLoadRoundTripPowerPreferences()
    {
        var temp = Path.Combine(Path.GetTempPath(), $"relay-prefs-{Guid.NewGuid():N}.json");
        try
        {
            var (ok, saved, warnings) = ClientPreferences.Save(new ClientPreferences
            {
                EnableIpv6Tunnel = false,
                CustomDnsOverride = " 9.9.9.9 ",
            }, temp);

            Assert.True(ok);
            Assert.Empty(warnings);
            Assert.False(saved.EnableIpv6Tunnel);
            Assert.Equal("9.9.9.9", saved.CustomDnsOverride);

            var reloaded = ClientPreferences.LoadOrDefault(temp);
            Assert.Equal(saved, reloaded);
        }
        finally
        {
            try { File.Delete(temp); } catch { }
            try { File.Delete(temp + ".tmp"); } catch { }
        }
    }

    [Fact]
    public void InvalidCustomDnsIsDroppedAndIpv6OptOutStripsV6Routes()
    {
        var clean = new ClientPreferences
        {
            EnableIpv6Tunnel = false,
            CustomDnsOverride = "not-an-ip",
        }.Sanitize();

        Assert.Null(clean.CustomDnsOverride);

        var overridden = new ClientPreferences
        {
            EnableIpv6Tunnel = false,
            CustomDnsOverride = "9.9.9.9",
        }.ApplyTo(new WgParams
        {
            ServerPublicKey = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=",
            ClientPrivateKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            AllowedIps = "0.0.0.0/0, ::/0",
            EndpointPort = 51820,
            Dns = "1.1.1.1",
            ClientAddress = "10.13.37.2/32",
            ClientIpv6 = "fd13:37::2/128",
            DnsIpv6 = "fd13:37::1",
        });

        Assert.Equal("9.9.9.9", overridden.Dns);
        Assert.Equal("0.0.0.0/0", overridden.AllowedIps);
        Assert.Null(overridden.ClientIpv6);
        Assert.Null(overridden.DnsIpv6);
    }
}
