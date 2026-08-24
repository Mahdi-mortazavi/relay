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
    UpdateInstaller? installer = null)
{
    /// <summary>
    /// Long enough that launching Relay never waits on GitHub, short enough
    /// that someone who opens it and leaves it running finds out today.
    /// </summary>
    public static readonly TimeSpan FirstCheckDelay = TimeSpan.FromMinutes(2);

    /// <summary>
    /// A day. Relay is a tray app that can run for weeks, so checking only at
    /// startup would never fire for the people most likely to fall behind.
    /// </summary>
    public static readonly TimeSpan Interval = TimeSpan.FromHours(24);

    /// <summary>How often to look for an idle moment once an update is waiting.</summary>
    public static readonly TimeSpan IdlePoll = TimeSpan.FromMinutes(1);

    /// <summary>The state name that means "nothing would be lost by restarting".</summary>
    private const string Idle = "Idle";

    private readonly UpdateInstaller _installer = installer ?? new UpdateInstaller();
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

        // Wait for a moment where replacing the app costs nothing — bounded, so
        // a machine that stays connected all day tries again next cycle instead
        // of holding a thread forever.
        var deadline = DateTimeOffset.UtcNow + Interval;
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

        var outcome = await _installer.InstallAsync(update, token: token).ConfigureAwait(false);
        switch (outcome)
        {
            case UpdateInstaller.Outcome.Started:
                notify(UpdateNotice.Installing, update.Version);
                break;
            case UpdateInstaller.Outcome.ChecksumMismatch:
                // Never retried quietly: the bytes on offer were not the bytes
                // the release published.
                notify(UpdateNotice.Refused, update.Version);
                break;
            case UpdateInstaller.Outcome.Unverifiable:
            case UpdateInstaller.Outcome.Unavailable:
                // Try again on the next cycle; neither is worth a toast.
                break;
        }
    }
}
