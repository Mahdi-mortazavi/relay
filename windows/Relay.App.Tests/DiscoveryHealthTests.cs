using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// Which empty an empty list is.
///
/// "Open Relay on your phone and tap Start Sharing" is correct for one of three
/// situations and misleading in the other two — and in both of those the person
/// will follow it, watch nothing happen, and conclude the two apps cannot see
/// each other. The PC-side twin of the phone's LinkSnapshot.
/// </summary>
public class DiscoveryHealthTests
{
    [Fact]
    public void APcThatNeverListenedDoesNotBlameThePhone()
    {
        // Port 47654 taken, or a policy that forbids the bind. Discovery failing
        // was already survivable and already logged; what it was not was *said*,
        // so the fallbacks that do work only helped someone who already knew
        // about them.
        Assert.Equal(
            DiscoveryHealth.State.NotListening,
            DiscoveryHealth.Diagnose(listening: false, onANetwork: () => true));
    }

    [Fact]
    public void NotBeingOnANetworkIsItsOwnAnswer()
    {
        // Overwhelmingly: they pressed Start Sharing on the phone and have not
        // joined its hotspot. Telling them to press Start Sharing again is
        // advice for a problem they have already solved.
        Assert.Equal(
            DiscoveryHealth.State.NoNetwork,
            DiscoveryHealth.Diagnose(listening: true, onANetwork: () => false));
    }

    [Fact]
    public void ListeningOnANetworkKeepsTheMessageItAlwaysHad()
    {
        // The genuinely common case, and the one the old text was written for.
        // A change that improved the two above by making this one worse would
        // have made the product worse overall.
        Assert.Equal(
            DiscoveryHealth.State.Listening,
            DiscoveryHealth.Diagnose(listening: true, onANetwork: () => true));
    }

    [Fact]
    public void ThePcIsNotAskedAboutItsNetworkWhenItCouldNotListenAnyway()
    {
        // Answering walks every adapter on the machine, and this runs on the UI
        // thread every time an empty list is drawn. More than cost: a PC that
        // cannot listen has been diagnosed already, and a second question whose
        // answer changes nothing is a second chance to be wrong.
        var asked = false;

        DiscoveryHealth.Diagnose(listening: false, onANetwork: () => { asked = true; return true; });

        Assert.False(asked, "the network was walked for a PC that had already been diagnosed");
    }

    [Fact]
    public void EveryStateHasItsOwnSentence()
    {
        // Two states sharing a key would silently reintroduce exactly the
        // one-message-for-three-situations problem this replaces, and every
        // other test here would still pass.
        var keys = Enum.GetValues<DiscoveryHealth.State>()
            .Select(DiscoveryHealth.StringKey)
            .ToList();

        Assert.Equal(keys.Count, keys.Distinct().Count());
        Assert.All(keys, key => Assert.False(string.IsNullOrWhiteSpace(key)));
    }

    [Fact]
    public void TheKeysAreKeys_NotEnglish()
    {
        // Relay.Core has no language. It returned English words once and they
        // went onto the Persian UI where nothing could translate them, which is
        // why LinkStringKey returns a key — and why this one does.
        Assert.All(
            Enum.GetValues<DiscoveryHealth.State>().Select(DiscoveryHealth.StringKey),
            key => Assert.DoesNotContain(' ', key));
    }

    [Fact]
    public void ThisMachineIsOnANetwork()
    {
        // Not a tautology: it asserts the probe can run at all and does not
        // throw, and that its filters have not become so strict that an
        // ordinary machine reads as offline — which would put "you aren't on a
        // network" in front of every user on a PC that plainly is.
        //
        // A CI runner has a network. If this ever fails there, the filter is
        // wrong, not the runner.
        Assert.True(DiscoveryHealth.OnANetwork());
    }
}
