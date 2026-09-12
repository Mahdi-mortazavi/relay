using System.Text;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// The phone's last word, on the one channel that survives being silenced.
///
/// When a phone's own VPN captures Relay, its unicast answers are routed into
/// the tunnel and never arrive, while its link-scoped broadcast bypasses the
/// tunnel and does. That asymmetry is the whole point of `blocked`: the PC is
/// the screen the person is looking at, and it can be told what to tell them.
///
/// Driven from /shared/test-vectors.json → beaconBlocked, so this side and the
/// phone's side cannot drift.
/// </summary>
public class BeaconBlockedTests
{
    /// <summary>A well-formed beacon, optionally carrying `blocked`.</summary>
    private static byte[] Beacon(string? blocked)
    {
        var field = blocked is null ? "" : $",\"blocked\":{System.Text.Json.JsonSerializer.Serialize(blocked)}";
        return Encoding.UTF8.GetBytes(
            $$"""
            {"v":1,"code":"42","mode":"wireguard","host":"192.168.1.14","port":51820,
             "state":"sharing","pairingPort":47655,"link":"wifi"{{field}}}
            """);
    }

    private static LanDiscovery.Device Parse(string? blocked)
    {
        Assert.True(LanDiscovery.TryParseBeacon(Beacon(blocked), DateTimeOffset.UnixEpoch, out var device, out _));
        return device!;
    }

    [Fact]
    public void EveryCaseInTheSharedVectors()
    {
        using var vectors = SharedContracts.Json("test-vectors.json");
        var section = vectors.RootElement.GetProperty("beaconBlocked");

        var seen = 0;
        foreach (var testCase in section.GetProperty("cases").EnumerateArray())
        {
            seen++;
            var name = testCase.GetProperty("name").GetString()!;
            var value = testCase.GetProperty("blocked");
            var blocked = value.ValueKind == System.Text.Json.JsonValueKind.Null ? null : value.GetString();
            var expected = testCase.GetProperty("accept").GetBoolean();

            // Whatever the value, the phone is still listed. It is genuinely
            // there and genuinely reachable; it is the reply that is not coming
            // back. Dropping the beacon would hide the phone *and* the reason.
            var device = Parse(blocked);

            Assert.True(expected == device.RepliesBlocked, name);
        }

        // A vectors file that silently stopped being read would make every
        // assertion above vacuous, and this file would still be green.
        Assert.True(seen >= 5, $"only {seen} case(s) read from beaconBlocked");
    }

    [Fact]
    public void AnUnknownCauseIsIgnored_NotTreatedAsBlocked()
    {
        // A later version may name a second cause. A client that read an
        // unfamiliar value as "blocked" would refuse to connect to a phone that
        // was fine; one that dropped the beacon would hide it entirely.
        var device = Parse("captive-portal");

        Assert.False(device.RepliesBlocked);
        Assert.Equal("42", device.Code);
    }

    [Fact]
    public void TheContractDoesNotNameTheCauseOnTheWire()
    {
        // The beacon is unauthenticated and broadcast to the whole link.
        // "lockdown" or "vpn" would tell a café that this phone's owner runs a
        // VPN, which for the people Relay is built for is not a neutral fact.
        // The explanation is a local string on this PC, shown to one person.
        Assert.Equal("replies", LanDiscovery.BlockedReplies);
        Assert.False(Parse("lockdown").RepliesBlocked);
        Assert.False(Parse("vpn").RepliesBlocked);
    }

    [Fact]
    public void ASilentPhoneIsNotAssumedWell()
    {
        // Absent means "nothing known to be wrong" — an older phone, or one that
        // could not read the setting. It must never light the warning, and it
        // must never block the connection either.
        Assert.False(Parse(null).RepliesBlocked);
    }

    // Both languages of the three new strings are covered by
    // StringsCoverageTests, which scans the app's source rather than
    // referencing it: this project references Relay.Core only, so the Strings
    // type is not reachable from here at all. Its ApplyError rule reads the
    // switch arm these keys live in, which is exactly the shape that once
    // shipped rendering raw keys for four releases.
}
