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
    private static byte[] Beacon(string code, string host, int port, string? link, string name = "SM-A307FN") =>
        Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["v"] = 1,
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
        Assert.Null(parsed.LinkLabel);
    }

    [Fact]
    public void TwoDifferentPhonesSharingACodeAreStillTwoPhones()
    {
        // The collapse must not go too far. Ninety codes and two phones collide
        // about one time in forty-five, and when they do the person has to be
        // asked which one — that is what ERR_CODE_AMBIGUOUS is for.
        var now = DateTimeOffset.UnixEpoch.AddHours(1);
        var discovery = new LanDiscovery(() => now);

        Assert.True(LanDiscovery.TryParseBeacon(
            Beacon("42", "192.168.1.14", 51820, "wifi", name: "Pixel"), now, out var first, out _));
        Assert.True(LanDiscovery.TryParseBeacon(
            Beacon("42", "192.168.1.55", 51820, "wifi", name: "Galaxy"), now, out var second, out _));

        discovery.Observe(first!, now);
        discovery.Observe(second!, now);

        Assert.Equal(2, discovery.MatchPhones("42").Count);
    }
}
