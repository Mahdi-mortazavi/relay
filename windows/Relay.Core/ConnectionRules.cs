namespace Relay.Core;

/// <summary>
/// The canonical connection state machine. The unit test asserts this table is
/// exactly /shared/connection-states.json — edit that file first.
/// </summary>
public static class ConnectionRules
{
    public static readonly IReadOnlySet<string> States =
        new HashSet<string> { "Idle", "Preparing", "Advertising", "Connected", "Error" };

    public const string Initial = "Idle";

    public static readonly IReadOnlyDictionary<(string From, string On), string> Transitions =
        new Dictionary<(string, string), string>
        {
            [("Idle", "start")] = "Preparing",
            [("Preparing", "ready")] = "Advertising",
            [("Preparing", "failure")] = "Error",
            [("Preparing", "stop")] = "Idle",
            [("Advertising", "clientConnected")] = "Connected",
            [("Advertising", "stop")] = "Idle",
            [("Advertising", "failure")] = "Error",
            [("Connected", "clientCountChanged")] = "Connected",
            [("Connected", "lastClientDisconnected")] = "Advertising",
            [("Connected", "stop")] = "Idle",
            [("Connected", "failure")] = "Error",
            [("Error", "dismiss")] = "Idle",
            [("Error", "retry")] = "Preparing",
        };

    /// <summary>Illegal events must be ignored by callers, never applied.</summary>
    public static bool CanTransition(string from, string on) => Transitions.ContainsKey((from, on));

    public static string? Target(string from, string on) =>
        Transitions.TryGetValue((from, on), out var to) ? to : null;
}
