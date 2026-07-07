namespace Relay.Core.Proxy;

/// <summary>
/// Complete value of the system proxy configuration Relay touches. Records
/// compare by value, which is exactly the "read back and assert equal"
/// verification the safety invariant requires (AC1.4).
/// </summary>
public sealed record ProxySnapshot(
    bool Enabled,
    string? Server,
    string? Override,
    string? AutoConfigUrl);

/// <summary>What we saved before touching the system, plus what we applied.</summary>
public sealed record ProxyBackup(ProxySnapshot Original, string AppliedServer);
