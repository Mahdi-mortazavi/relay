using System.Text;
using System.Text.Json;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// Choosing between links when a phone is reachable on more than one at once.
///
/// A phone with a cable in and Wi-Fi on announces on both, so the same code
/// arrives from two addresses a second apart, forever. The older rule — "a
/// different host with the same code is the phone at a new address" — reads
/// that as a phone moving house twice a second, and a client following it
/// re-points its tunnel back and forth for as long as both links are up.
///
/// The cases come from /shared/test-vectors.json so the Android side is held to
/// the same answers, which is the point of that file.
/// </summary>
public class BeaconPathTests
{
    private static byte[] Beacon(string code, string host, int port, string? link, string name = "SM-A307FN", int version = 1) =>
        Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["v"] = version,
            ["code"] = code,
            ["mode"] = "wireguard",
            ["host"] = host,
            ["port"] = port,
            ["name"] = name,
            ["state"] = "sharing",
            ["pairingPort"] = 47655,
            ["link"] = link,
        }.Where(pair => pair.Value is not null).ToDictionary(pair => pair.Key, pair => pair.Value)));

    [Fact]
    public void EveryCaseInTheSharedVectors()
    {
        using var vectors = SharedContracts.Json("test-vectors.json");
        var section = vectors.RootElement.GetProperty("beaconPaths");

        // The ages below are expressed against this window, so the two have to
        // be the same number. If someone widens the contract's window without
        // widening Stale, every "still fresh" case here quietly becomes a
        // "roamed" case and goes on passing for the wrong reason.
        Assert.Equal(section.GetProperty("windowMs").GetInt32(), (int)LanDiscovery.Stale.TotalMilliseconds);

        foreach (var testCase in section.GetProperty("cases").EnumerateArray())
        {
            var name = testCase.GetProperty("name").GetString()!;
            var now = DateTimeOffset.UnixEpoch.AddHours(1);

            // The clock is fixed and each beacon is aged by its own freshMs, so
            // "stale" means what the contract says it means rather than however
            // long the test took to run.
            var discovery = new LanDiscovery(() => now);

            foreach (var beacon in testCase.GetProperty("beacons").EnumerateArray())
            {
                var freshMs = beacon.GetProperty("freshMs").GetInt32();
                var seen = now.AddMilliseconds(-freshMs);
                var link = beacon.TryGetProperty("link", out var l) ? l.GetString() : null;

                Assert.True(
                    LanDiscovery.TryParseBeacon(
                        Beacon(beacon.GetProperty("code").GetString()!,
                               beacon.GetProperty("host").GetString()!,
                               beacon.GetProperty("port").GetInt32(),
                               link),
                        seen, out var parsed, out _),
                    $"{name}: a beacon from the shared vectors did not parse");

                discovery.Observe(parsed!, seen);
            }

            var forCode = testCase.TryGetProperty("forCode", out var fc)
                ? fc.GetString()!
                : testCase.GetProperty("beacons")[0].GetProperty("code").GetString()!;

            var chosen = discovery.BestPath(forCode);
            Assert.NotNull(chosen);
            Assert.Equal(testCase.GetProperty("expectHost").GetString(), chosen!.Host);

            // Whatever else happened, a phone reachable two ways is still one
            // phone. Listing it twice is what made the pairing screen ask the
            // person to choose between two rows that were the same device.
            Assert.Single(discovery.MatchPhones(forCode));

            // And the distinction the whole section exists for: roaming means
            // the old address is *gone*, two paths means both are still there.
            // Without this the two read identically from the outside, since
            // either way the best path is the one the case expects.
            var pathsForCode = discovery.Match(forCode).Count;
            var expectRoamed = testCase.GetProperty("expectRoamed").GetBoolean();
            var beaconsForCode = testCase.GetProperty("beacons").EnumerateArray()
                .Count(b => b.GetProperty("code").GetString() == forCode);

            if (expectRoamed)
                Assert.True(pathsForCode < beaconsForCode,
                    $"{name}: the address it moved off should have expired, but {pathsForCode} paths remain");
            else
                Assert.Equal(beaconsForCode, pathsForCode);
        }
    }

    [Fact]
    public void ARankIsGivenToEveryLinkTheContractNames()
    {
        // Ordering, not the numbers themselves: a cable beats the radios, and a
        // beacon with no link sorts last because a phone that does not send one
        // is announcing the single address it always did.
        LanDiscovery.Device At(string? link) =>
            new("42", "wireguard", "192.168.1.14", 51820, "phone", DateTimeOffset.UnixEpoch, 47655, link);

        Assert.True(At("usb").PathRank > At("wifi").PathRank);
        Assert.True(At("wifi").PathRank > At("hotspot").PathRank);
        Assert.True(At("hotspot").PathRank > At(null).PathRank);
        Assert.Equal(At("something-else").PathRank, At(null).PathRank);
    }

    [Fact]
    public void AnUnknownLinkIsDroppedRatherThanShown()
    {
        // Every field here is attacker-controlled. A value outside the three the
        // contract names is a phone speaking a dialect this build does not know,
        // and it must not reach a label.
        Assert.True(LanDiscovery.TryParseBeacon(
            Beacon("42", "192.168.1.14", 51820, "carrier-pigeon"),
            DateTimeOffset.UnixEpoch, out var parsed, out _));
        Assert.Null(parsed!.Link);
        Assert.Null(parsed.LinkStringKey);
    }

    [Fact]
    public void ConnectedOverWifiKeepsSayingWifiWhenACableIsAlsoIn()
    {
        // The address line answers "how am I connected", not "how would I
        // rather connect". Asking Devices — which ranks the cable first, because
        // that is the path to prefer next time — would relabel a working Wi-Fi
        // session as USB the moment someone plugged in to charge.
        var now = DateTimeOffset.UnixEpoch.AddHours(1);
        var discovery = new LanDiscovery(() => now);

        foreach (var (host, link) in new[] { ("192.168.1.14", "wifi"), ("192.168.42.129", "usb") })
        {
            Assert.True(LanDiscovery.TryParseBeacon(Beacon("42", host, 51820, link), now, out var d, out _));
            discovery.Observe(d!, now);
        }

        Assert.Equal("LinkWifi", discovery.LinkStringKeyFor("192.168.1.14"));
        Assert.Equal("LinkUsb", discovery.LinkStringKeyFor("192.168.42.129"));
        Assert.Null(discovery.LinkStringKeyFor("192.168.1.99"));
    }

    [Fact]
    public void AStaleBeaconNoLongerSaysHowWeAreConnected()
    {
        // Beacons stopping is the phone leaving the network or Relay closing on
        // it. Either way the last thing it said about its links is no longer
        // something to put on screen as present tense.
        var now = DateTimeOffset.UnixEpoch.AddHours(1);
        var discovery = new LanDiscovery(() => now);

        Assert.True(LanDiscovery.TryParseBeacon(
            Beacon("42", "192.168.42.129", 51820, "usb"), now, out var device, out _));
        discovery.Observe(device!, now - LanDiscovery.Stale - TimeSpan.FromMilliseconds(1));

        Assert.Null(discovery.LinkStringKeyFor("192.168.42.129"));
    }

    [Fact]
    public void EveryVersionInTheSharedVectors()
    {
        // Found on hardware, not in review: Relay 2.7.1 keys a phone by its
        // address, so this branch's phone — announcing on a cable and Wi-Fi at
        // once — was listed twice by the released client, which then refused to
        // connect and told the person to "stop sharing on the one you don't
        // want", naming the same phone twice. v2 on the additional paths is what
        // keeps that client working.
        using var vectors = SharedContracts.Json("test-vectors.json");
        var section = vectors.RootElement.GetProperty("beaconVersions");

        foreach (var accepted in section.GetProperty("accepted").EnumerateArray())
        {
            Assert.Contains(accepted.GetInt32(), new[] { LanDiscovery.Version, LanDiscovery.VersionExtraPath });
        }

        foreach (var testCase in section.GetProperty("cases").EnumerateArray())
        {
            var name = testCase.GetProperty("name").GetString()!;
            var parsed = LanDiscovery.TryParseBeacon(
                Beacon("42", "192.168.1.14", 51820, "wifi", version: testCase.GetProperty("version").GetInt32()),
                DateTimeOffset.UnixEpoch, out _, out _);

            Assert.Equal(testCase.GetProperty("accept").GetBoolean(), parsed);
        }
    }

    [Fact]
    public void AVersionedSecondPathIsStillTheSamePhone()
    {
        // The other half of it: dropping v2 must not be the only thing keeping
        // this client sane. It accepts both versions, and the two still collapse
        // to one phone on its best path.
        var now = DateTimeOffset.UnixEpoch.AddHours(1);
        var discovery = new LanDiscovery(() => now);

        Assert.True(LanDiscovery.TryParseBeacon(
            Beacon("42", "192.168.99.48", 51820, "usb", version: LanDiscovery.Version),
            now, out var cable, out _));
        Assert.True(LanDiscovery.TryParseBeacon(
            Beacon("42", "192.168.1.14", 51820, "wifi", version: LanDiscovery.VersionExtraPath),
            now, out var wifi, out _));

        discovery.Observe(cable!, now);
        discovery.Observe(wifi!, now);

        Assert.Single(discovery.MatchPhones("42"));
        Assert.Equal("192.168.99.48", discovery.BestPath("42")!.Host);
    }

    [Fact]
    public void TheVersionsTheContractSaysThePhoneSendsAreTheOnesWeAccept()
    {
        // The `sends` half of the vectors: whatever version the phone puts on a
        // link, this client has to be able to read it. A contract that says the
        // phone sends v2 on Wi-Fi, against a client that only accepts v1, is a
        // path that exists on the wire and nowhere else.
        using var vectors = SharedContracts.Json("test-vectors.json");
        foreach (var link in vectors.RootElement
                     .GetProperty("beaconVersions").GetProperty("sends")
                     .GetProperty("links").EnumerateArray())
        {
            var version = link.GetProperty("version").GetInt32();
            Assert.True(
                LanDiscovery.TryParseBeacon(
                    Beacon("42", "192.168.1.14", 51820, link.GetProperty("link").GetString(), version: version),
                    DateTimeOffset.UnixEpoch, out _, out _),
                $"the phone sends v{version} on {link.GetProperty("link").GetString()} and this client rejects it");
        }
    }
}
