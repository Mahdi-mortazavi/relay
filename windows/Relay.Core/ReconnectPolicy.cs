namespace Relay.Core;

/// <summary>
/// Bounded auto-reconnect schedule. Contract: /shared/reconnect.md — any change
/// there first. A brief drop is absorbed silently; only exhausting the budget
/// surfaces an error (ADR-0007).
/// </summary>
public static class ReconnectPolicy
{
    /// <summary>Delay before each retry attempt, in order (milliseconds).</summary>
    public static readonly IReadOnlyList<int> AttemptDelaysMs = [1000, 2000, 2000, 3000, 3000];

    public static int Attempts => AttemptDelaysMs.Count;

    /// <summary>The whole retry window; recovery is expected within this bound.</summary>
    public static int TotalBoundMs => AttemptDelaysMs.Sum();
}
