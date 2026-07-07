using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

public class ReconnectPolicyTests
{
    private static readonly System.Text.Json.JsonElement Reconnect =
        SharedContracts.Json("test-vectors.json").RootElement.GetProperty("reconnect");

    [Fact]
    public void Schedule_matches_the_shared_contract()
    {
        var expected = Reconnect.GetProperty("attemptDelaysMs").EnumerateArray()
            .Select(e => e.GetInt32()).ToList();
        Assert.Equal(expected, ReconnectPolicy.AttemptDelaysMs);
    }

    [Fact]
    public void Attempt_count_matches_the_shared_contract()
    {
        Assert.Equal(Reconnect.GetProperty("attempts").GetInt32(), ReconnectPolicy.Attempts);
    }

    [Fact]
    public void Total_recovery_bound_matches_the_shared_contract()
    {
        Assert.Equal(Reconnect.GetProperty("totalBoundMs").GetInt32(), ReconnectPolicy.TotalBoundMs);
    }
}
