namespace Relay.Core.Proxy;

/// <summary>
/// OS proxy configuration access. The real implementation is the WinINet
/// registry store in Relay.App; tests use in-memory fakes.
/// </summary>
public interface IProxyStore
{
    ProxySnapshot Read();
    void Write(ProxySnapshot snapshot);

    /// <summary>Tells the OS settings changed (WinINet refresh). No-op in fakes.</summary>
    void NotifyChanged();
}

/// <summary>
/// Durable storage for the pre-connect snapshot so a crash or unexpected exit
/// can be rolled back on next start (safety invariant #2).
/// </summary>
public interface IBackupStore
{
    ProxyBackup? Load();
    void Save(ProxyBackup backup);
    void Delete();
}
