namespace Relay.Core;

/// <summary>What the person is being told, so the caller can say it in their language.</summary>
public enum UpdateNotice
{
    /// <summary>A newer release exists; it will install at the next idle moment.</summary>
    Available,

    /// <summary>Verified and handed to the installer.</summary>
    Installing,

    /// <summary>The download did not match the checksum the release published.</summary>
    Refused,
}

/// <summary>
/// Keeps Relay current on its own.
///
/// <see cref="UpdateCheck"/> and <see cref="UpdateInstaller"/> existed and were
/// tested for three releases with nothing calling either of them: the machinery
/// was built and never connected, so a Windows user stayed on whatever they
/// first installed while the README said updates were offered. This is the
/// wiring, and it lives in Core rather than in the app so a test can reach it —
/// being unreachable from the test project is part of how the gap survived.
///
/// Two rules shape it.
///
/// <b>Never interrupt a connection.</b> The installer stops Relay in order to
/// replace it, which would drop a live tunnel and whatever was going through
/// it. So installing waits for idle. Checking does not: knowing early is free.
///
/// <b>Never install what was not verified.</b> That is
/// <see cref="UpdateInstaller"/>'s job, and it refuses on a hash mismatch or a
/// release with no published checksums. This class only decides <em>when</em>.
/// </summary>
public sealed class UpdateService(
    string currentVersion,
    Func<string> currentState,
    Action<UpdateNotice, string> notify,
    Func<UpdateCheck>? checkFactory = null,
    UpdateInstaller? installer = null,
    TimeSpan? idleWait = null,
    string? downloadDirectory = null,
    Action<string>? runInstaller = null)
{
    /// <summary>
    /// Long enough that launching Relay is not competing with a download while
    /// someone is trying to connect, short enough that a session which lasts a
    /// couple of minutes still checks at all.
    ///
    /// It was two minutes, and that turned out to be most of a bug. Relay is
    /// opened to be connected to, and plenty of sessions are shorter than two
    /// minutes end to end — for those the check never ran once, on any launch,
    /// ever. The delay is not what keeps the launch fast; the check has always
    /// been on a background thread.
    /// </summary>
    public static readonly TimeSpan FirstCheckDelay = TimeSpan.FromSeconds(30);

    /// <summary>
    /// How long to let a launch settle before installing something that was
    /// downloaded on an earlier one.
    ///
    /// Short, because this is the moment the whole design turns on. Not zero,
    /// because the app closing itself before its window has finished appearing
    /// looks like a crash rather than an update, and the toast that explains it
    /// needs somewhere to land.
    /// </summary>
    public static readonly TimeSpan PendingInstallDelay = TimeSpan.FromSeconds(3);

    /// <summary>
    /// A day. Relay is a tray app that can run for weeks, so checking only at
    /// startup would never fire for the people most likely to fall behind.
    /// </summary>
    public static readonly TimeSpan Interval = TimeSpan.FromHours(24);

    /// <summary>How often to look for an idle moment once an update is waiting.</summary>
    public static readonly TimeSpan IdlePoll = TimeSpan.FromMinutes(1);

    /// <summary>
    /// How long to keep looking for that idle moment before giving up until the
    /// next cycle. Injectable only so a test can say "do not wait", which is
    /// what makes the never-install-while-connected rule assertable in
    /// milliseconds rather than a day.
    /// </summary>
    private readonly TimeSpan _idleWait = idleWait ?? Interval;

    /// <summary>The state name that means "nothing would be lost by restarting".</summary>
    private const string Idle = "Idle";

    private readonly UpdateInstaller _installer = installer ?? new UpdateInstaller();
    private readonly string _directory = downloadDirectory ?? PendingUpdate.DefaultDirectory;
    private CancellationTokenSource? _loop;

    /// <summary>The version already announced, so a daily check does not nag.</summary>
    private string? _announced;

    public void Start()
    {
        if (_loop is not null) return;
        var loop = new CancellationTokenSource();
        _loop = loop;
        _ = Task.Run(() => RunAsync(loop.Token), loop.Token);
    }

    public void Stop()
    {
        try { _loop?.Cancel(); } catch (Exception) { }
        _loop?.Dispose();
        _loop = null;
    }

    private async Task RunAsync(CancellationToken token)
    {
        try
        {
            // Before anything is checked or fetched: an installer this app
            // already downloaded and verified on some earlier launch.
            //
            // This is the moment the previous design never had. Installing was
            // only ever attempted inside one run of the process — start, wait,
            // check, download, wait for Idle — so it depended on a coincidence:
            // the app had to still be open, long enough, and idle at the right
            // time. Someone who opens Relay, connects immediately and closes it
            // when they are done satisfies that never. Start-up satisfies it by
            // definition, because nothing is connected yet.
            await Task.Delay(PendingInstallDelay, token).ConfigureAwait(false);
            if (await InstallPendingAsync(relaunch: true, token).ConfigureAwait(false)) return;

            await Task.Delay(FirstCheckDelay, token).ConfigureAwait(false);
            while (!token.IsCancellationRequested)
            {
                await CheckAndMaybeInstallAsync(token).ConfigureAwait(false);
                await Task.Delay(Interval, token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // Exiting: the ordinary way out.
        }
        catch (Exception)
        {
            // An updater must never be why the app falls over.
        }
    }

    /// <summary>One cycle. Public so a test can drive it without waiting a day.</summary>
    public async Task CheckAndMaybeInstallAsync(CancellationToken token = default)
    {
        var check = checkFactory?.Invoke() ?? new UpdateCheck(currentVersion);

        UpdateCheck.Available? update;
        try
        {
            update = await check.CheckAsync(token).ConfigureAwait(false);
        }
        catch (Exception)
        {
            return; // offline is not news
        }
        if (update is null) return;

        if (_announced != update.Version)
        {
            _announced = update.Version;
            notify(UpdateNotice.Available, update.Version);
        }

        // Already on disk, from this launch or an earlier one.
        //
        // Without this the daily check pulls fifty megabytes again to arrive at
        // exactly the bytes already sitting in the download directory — every
        // day, for as long as the install has nowhere to happen. On the
        // connections Relay is built for, that traffic is the user's phone data.
        var pending = PendingUpdate.Load(_directory);
        if (pending is not null && pending.Version == update.Version &&
            File.Exists(Path.Combine(_directory, pending.Installer)))
        {
            await InstallPendingAsync(relaunch: true, token).ConfigureAwait(false);
            return;
        }

        // Fetch first, connected or not.
        //
        // This used to wait for idle before downloading, and on the networks
        // Relay is built for that meant it could never update at all: the
        // tunnel is frequently the only route to GitHub's release CDN, so the
        // one moment it was willing to download was the one moment the bytes
        // were unreachable. Measured on a connection that answers
        // api.github.com in under a second and cannot open the asset host at
        // all. Only running the installer has to wait, because only running it
        // costs someone their connection.
        var (outcome, installer, sha256) = await _installer
            .DownloadAsync(update, _directory, token).ConfigureAwait(false);

        if (installer is null)
        {
            if (outcome == UpdateInstaller.Outcome.ChecksumMismatch)
            {
                // Never retried quietly: the bytes on offer were not the bytes
                // the release published.
                notify(UpdateNotice.Refused, update.Version);
            }
            // Unverifiable and Unavailable both mean "try again next cycle",
            // and neither is worth interrupting anyone about.
            return;
        }

        // Write down what is on disk before trying to use it.
        //
        // Everything below can fail to install for perfectly ordinary reasons —
        // a tunnel that stays up all day, the app being closed mid-wait — and
        // before this line those bytes were simply forgotten, then fetched
        // again on the next launch and forgotten again. Recording them is what
        // lets the app closing, or opening, be the thing that finishes the job.
        PendingUpdate.Save(_directory, new PendingUpdate(
            update.Version, Path.GetFileName(installer), sha256 ?? string.Empty));

        // Now wait for a moment where replacing the app costs nothing —
        // bounded, so a machine that stays connected all day tries again next
        // cycle instead of holding a thread forever. The download is already on
        // disk and verified, so that next cycle is cheap.
        var deadline = DateTimeOffset.UtcNow + _idleWait;
        while (currentState() != Idle && DateTimeOffset.UtcNow < deadline)
        {
            try
            {
                await Task.Delay(IdlePoll, token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
        if (currentState() != Idle) return;

        // Cleared first: if Run fails, the next cycle should find out for itself
        // rather than a later launch retrying bytes that already would not go.
        PendingUpdate.Clear(_directory);
        if (_installer.Run(installer, runInstaller) == UpdateInstaller.Outcome.Started)
        {
            notify(UpdateNotice.Installing, update.Version);
        }
    }

    /// <summary>
    /// Runs an update that an earlier session downloaded and verified, if there
    /// is one and this is a moment where running it costs nothing.
    ///
    /// Called at two points, both chosen because they are certain to happen
    /// rather than likely to: when the app starts, and when the app is closing.
    /// Between them they cover the case this was written for — a person who
    /// opens Relay, connects straight away, and quits when they are finished,
    /// who under the old design never updated at all.
    ///
    /// Public so the app can call it on the way out, and so a test can drive it
    /// without a launch.
    /// </summary>
    /// <param name="relaunch">
    /// Whether Setup should start Relay again afterwards. True at start-up, so
    /// the app the user just opened comes back. False on the way out: they
    /// asked it to close.
    /// </param>
    /// <returns>True when an installer was actually started.</returns>
    public async Task<bool> InstallPendingAsync(bool relaunch, CancellationToken token = default)
    {
        var pending = PendingUpdate.Load(_directory);
        if (pending is null) return false;

        var path = Path.Combine(_directory, pending.Installer);

        // Already current. The ordinary way here is the happy one: the update
        // installed, this build *is* that version, and the note is stale. Also
        // covers someone who installed a newer build by hand in the meantime.
        var pendingVersion = UpdateCheck.Parse(pending.Version);
        var current = UpdateCheck.Parse(currentVersion);
        if (pendingVersion is null || current is null ||
            UpdateCheck.Compare(pendingVersion, current) <= 0)
        {
            Forget(path);
            return false;
        }

        if (!File.Exists(path))
        {
            // A temp directory that Windows swept, most likely. Nothing is
            // wrong; the next check downloads it again.
            PendingUpdate.Clear(_directory);
            return false;
        }

        // Verified once at download time, and again here. In between it has sat
        // in a directory anything running as this user can write to, across at
        // least one launch and possibly a reboot, and what it does when run is
        // replace the program that carries the user's whole connection.
        string actual;
        try
        {
            actual = await UpdateInstaller.HashFileAsync(path, token).ConfigureAwait(false);
        }
        catch (Exception)
        {
            return false; // unreadable right now; say nothing, try next launch
        }

        if (!UpdateInstaller.Matches(actual, pending.Sha256))
        {
            // Never quietly retried: these are not the bytes the release
            // published, whatever the reason.
            Forget(path);
            notify(UpdateNotice.Refused, pending.Version);
            return false;
        }

        // Start-up is Idle by construction and so is a close that has already
        // disconnected, but neither is worth assuming — an auto-connect at login
        // would make the first one false.
        if (currentState() != Idle) return false;

        PendingUpdate.Clear(_directory);
        if (_installer.Run(path, runInstaller, relaunch) != UpdateInstaller.Outcome.Started) return false;

        notify(UpdateNotice.Installing, pending.Version);
        return true;
    }

    /// <summary>Drops the record and the installer it names. Never throws.</summary>
    private void Forget(string installerPath)
    {
        PendingUpdate.Clear(_directory);
        try { if (File.Exists(installerPath)) File.Delete(installerPath); } catch (Exception) { }
    }
}
