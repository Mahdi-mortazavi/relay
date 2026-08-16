using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

public class ConnectionRulesTests
{
    private static readonly System.Text.Json.JsonElement Shared =
        SharedContracts.Json("connection-states.json").RootElement;

    [Fact]
    public void States_and_initial_state_match_the_shared_contract()
    {
        var sharedStates = Shared.GetProperty("states").EnumerateArray()
            .Select(s => s.GetString()!).ToHashSet();
        Assert.Equal(sharedStates, ConnectionRules.States);
        Assert.Equal(Shared.GetProperty("initial").GetString(), ConnectionRules.Initial);
    }

    [Fact]
    public void Transition_table_matches_the_shared_contract_exactly()
    {
        var sharedTransitions = Shared.GetProperty("transitions").EnumerateArray()
            .ToDictionary(
                t => (t.GetProperty("from").GetString()!, t.GetProperty("on").GetString()!),
                t => t.GetProperty("to").GetString()!);
        Assert.Equal(sharedTransitions.Count, ConnectionRules.Transitions.Count);
        foreach (var ((from, on), to) in sharedTransitions)
        {
            Assert.Equal(to, ConnectionRules.Target(from, on));
        }
    }

    [Fact]
    public void Undefined_transitions_are_illegal()
    {
        Assert.False(ConnectionRules.CanTransition("Idle", "clientConnected"));
        Assert.False(ConnectionRules.CanTransition("Connected", "ready"));
        Assert.False(ConnectionRules.CanTransition("Error", "stop"));
    }

    /// <summary>
    /// Reaching the adapter is not reaching Connected.
    ///
    /// Full Mode dispatched "ready" and stopped, which leaves the machine at
    /// Advertising -- and the window, which is a pure projection of this state,
    /// sat on "Connecting…" with a Cancel button while a live tunnel carried
    /// real traffic. Found only by looking at the running app on hardware: the
    /// log line said "Connected (Full Mode)" and that is what everyone read.
    ///
    /// This asserts the shape of the journey rather than the call site, so it
    /// still holds if the transport is rewritten again.
    /// </summary>
    [Fact]
    public void ReachingConnectedTakesTwoTransitionsNotOne()
    {
        var afterReady = ConnectionRules.Target("Preparing", "ready");
        Assert.Equal("Advertising", afterReady);
        Assert.NotEqual("Connected", afterReady);

        // The second one is the whole difference between a UI that says
        // "Connecting…" forever and a UI that tells the truth.
        Assert.Equal("Connected", ConnectionRules.Target("Advertising", "clientConnected"));
    }
}
