using Relay.Core.Proxy;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// The transactional-proxy safety invariant (AC1.4): every apply is verified
/// by read-back; disconnect restores the exact pre-connect snapshot; a crash
/// is recovered on next start without clobbering user changes.
/// </summary>
public class ProxySessionTests
{
    private sealed class FakeProxyStore : IProxyStore
    {
        public ProxySnapshot Current = new(false, null, null, null);
        public int NotifyCount;

        /// <summary>When set, writes are silently dropped — simulates a failing OS layer.</summary>
        public bool WritesFail;

        public ProxySnapshot Read() => Current;

        public void Write(ProxySnapshot snapshot)
        {
            if (!WritesFail) Current = snapshot;
        }

        public void NotifyChanged() => NotifyCount++;
    }

    private sealed class FakeBackupStore : IBackupStore
    {
        public ProxyBackup? Stored;
        public ProxyBackup? Load() => Stored;
        public void Save(ProxyBackup backup) => Stored = backup;
        public void Delete() => Stored = null;
    }

    private static readonly ProxySnapshot UserSnapshot =
        new(true, "http=corp-proxy:8080", "intranet;<local>", "http://corp/proxy.pac");

    [Fact]
    public void Connect_applies_socks_proxy_and_persists_backup_first()
    {
        var store = new FakeProxyStore { Current = UserSnapshot };
        var backup = new FakeBackupStore();
        var session = new ProxySession(store, backup);

        var result = session.Connect("192.168.43.1", 1080);

        Assert.True(result.Ok);
        Assert.Equal(ProxySession.AppliedFor("192.168.43.1", 1080), store.Current);
        Assert.NotNull(backup.Stored);
        Assert.Equal(UserSnapshot, backup.Stored!.Original);
        Assert.True(store.NotifyCount > 0);
    }

    [Fact]
    public void Disconnect_restores_the_exact_pre_connect_snapshot()
    {
        var store = new FakeProxyStore { Current = UserSnapshot };
        var backup = new FakeBackupStore();
        var session = new ProxySession(store, backup);

        session.Connect("192.168.43.1", 1080);
        var result = session.Disconnect();

        Assert.True(result.Ok);
        Assert.Equal(UserSnapshot, store.Current); // read-back equality: AC1.4
        Assert.Null(backup.Stored);                // transaction closed
    }

    [Fact]
    public void Failed_apply_rolls_back_and_reports()
    {
        var store = new FakeProxyStore { Current = UserSnapshot, WritesFail = true };
        var backup = new FakeBackupStore();
        var session = new ProxySession(store, backup);

        var result = session.Connect("192.168.43.1", 1080);

        Assert.False(result.Ok);
        Assert.Equal("ERR_PROXY_APPLY_FAILED", result.ErrorCode);
        Assert.Equal(UserSnapshot, store.Current);
        Assert.Null(backup.Stored);
    }

    [Fact]
    public void Failed_rollback_keeps_the_backup_for_retry()
    {
        var store = new FakeProxyStore { Current = UserSnapshot };
        var backup = new FakeBackupStore();
        var session = new ProxySession(store, backup);

        session.Connect("192.168.43.1", 1080);
        store.WritesFail = true;
        var result = session.Disconnect();

        Assert.False(result.Ok);
        Assert.Equal("ERR_ROLLBACK_INCOMPLETE", result.ErrorCode);
        Assert.NotNull(backup.Stored); // retry stays possible

        store.WritesFail = false;
        Assert.True(session.Disconnect().Ok);
        Assert.Equal(UserSnapshot, store.Current);
    }

    [Fact]
    public void Crash_recovery_restores_snapshot_when_proxy_is_still_ours()
    {
        var store = new FakeProxyStore { Current = UserSnapshot };
        var backup = new FakeBackupStore();
        new ProxySession(store, backup).Connect("192.168.43.1", 1080);
        // App "crashes" here: backup exists, proxy still points at us.

        var recovered = new ProxySession(store, backup).RecoverIfCrashed();

        Assert.True(recovered);
        Assert.Equal(UserSnapshot, store.Current);
        Assert.Null(backup.Stored);
    }

    [Fact]
    public void Crash_recovery_respects_user_changes_made_after_the_crash()
    {
        var store = new FakeProxyStore { Current = UserSnapshot };
        var backup = new FakeBackupStore();
        new ProxySession(store, backup).Connect("192.168.43.1", 1080);
        var userChosen = new ProxySnapshot(false, null, null, null);
        store.Current = userChosen; // user reconfigured after the crash

        var recovered = new ProxySession(store, backup).RecoverIfCrashed();

        Assert.True(recovered);
        Assert.Equal(userChosen, store.Current); // their change wins
        Assert.Null(backup.Stored);              // stale backup discarded
    }

    [Fact]
    public void Recovery_without_backup_is_a_no_op()
    {
        var store = new FakeProxyStore { Current = UserSnapshot };
        var session = new ProxySession(store, new FakeBackupStore());
        Assert.False(session.RecoverIfCrashed());
        Assert.Equal(UserSnapshot, store.Current);
    }

    [Fact]
    public void Disconnect_without_connect_is_a_no_op()
    {
        var store = new FakeProxyStore { Current = UserSnapshot };
        var session = new ProxySession(store, new FakeBackupStore());
        Assert.True(session.Disconnect().Ok);
        Assert.Equal(UserSnapshot, store.Current);
    }
}
