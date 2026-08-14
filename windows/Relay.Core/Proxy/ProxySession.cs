namespace Relay.Core.Proxy;

/// <summary>
/// Transactional system-proxy changes (safety invariant #2): snapshot before
/// touching anything, persist the snapshot durably, verify every write by
/// reading it back, and restore the snapshot on disconnect, failure, or — via
/// <see cref="RecoverIfCrashed"/> — after a crash.
/// </summary>
public sealed class ProxySession(IProxyStore store, IBackupStore backup)
{
    /// <summary>Error codes are the stable identifiers from docs/errors.md.</summary>
    public sealed record Result(bool Ok, string? ErrorCode = null)
    {
        public static readonly Result Success = new(true);
        public static Result Fail(string code) => new(false, code);
    }

    /// <summary>
    /// The registry value WinINET consumers read. The scheme prefix is not
    /// decoration: in WinINET's syntax a bare <c>socks=host:port</c> means
    /// SOCKS<b>4</b>, and Chromium hard-codes that reading — see
    /// net/proxy_resolution/proxy_config.cc, where <c>url_scheme == "socks"</c>
    /// sets <c>default_scheme = SCHEME_SOCKS4</c>. The phone only ever speaks
    /// SOCKS5 (Socks5Server.kt rejects any version byte but 5), so every
    /// browser opened a socket, sent a SOCKS4 CONNECT, and had it dropped —
    /// while Relay's own probe, which speaks SOCKS5 by hand, kept reporting
    /// "Connected". That is the whole of the "only the SwitchyOmega extension
    /// works" reports: the extension bypasses this value entirely.
    ///
    /// Writing the scheme explicitly overrides the SOCKS4 default and costs
    /// nothing for consumers that ignore it.
    /// </summary>
    public static ProxySnapshot AppliedFor(string host, int port) =>
        new(Enabled: true, Server: $"socks=socks5://{host}:{port}", Override: "<local>", AutoConfigUrl: null);

    /// <summary>Applies the SOCKS system proxy; on any verification failure the original state is restored.</summary>
    public Result Connect(string host, int port)
    {
        var original = store.Read();
        var applied = AppliedFor(host, port);

        backup.Save(new ProxyBackup(original, applied.Server!));
        store.Write(applied);
        store.NotifyChanged();

        if (store.Read() != applied)
        {
            store.Write(original);
            store.NotifyChanged();
            backup.Delete();
            return Result.Fail("ERR_PROXY_APPLY_FAILED");
        }
        return Result.Success;
    }

    /// <summary>
    /// Restores the pre-connect snapshot and verifies by read-back (AC1.4).
    /// On verification failure the backup is kept so a retry is possible.
    /// </summary>
    public Result Disconnect()
    {
        var saved = backup.Load();
        if (saved is null) return Result.Success; // nothing was applied

        store.Write(saved.Original);
        store.NotifyChanged();

        if (store.Read() != saved.Original)
        {
            return Result.Fail("ERR_ROLLBACK_INCOMPLETE");
        }
        backup.Delete();
        return Result.Success;
    }

    /// <summary>
    /// Called on app start. If a backup exists, the previous run died without
    /// disconnecting: restore the snapshot — but only when the proxy still
    /// points at what we applied. If the user changed the proxy since the
    /// crash, their change wins and the stale backup is discarded.
    /// Returns true when a crash was detected.
    /// </summary>
    public bool RecoverIfCrashed()
    {
        var saved = backup.Load();
        // Treat a corrupt/partial backup (null contents) as "nothing to recover"
        // rather than dereferencing it and crashing startup.
        if (saved?.Original is null || saved.AppliedServer is null)
        {
            backup.Delete();
            return false;
        }

        var current = store.Read();
        if (current.Enabled && current.Server == saved.AppliedServer)
        {
            store.Write(saved.Original);
            store.NotifyChanged();
        }
        backup.Delete();
        return true;
    }
}
