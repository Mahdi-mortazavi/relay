using System.Net;
using System.Security.Cryptography;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// That an update which could not be installed *now* still gets installed.
///
/// This is the regression suite for a bug that was found on a real laptop
/// rather than in CI. That machine was running 1.9.3-test from 16 August while
/// 2.8.1 was published, and the reason was in <c>%TEMP%\Relay-update</c>: a
/// 48,536,231-byte installer, downloaded on 9 September and checksum-verified —
/// an unverified one is deleted — still sitting there, unrun, on 12 September.
///
/// Installing used to be attempted only inside one run of the process: start,
/// wait, check, download, then wait for the state to be Idle. Relay is opened
/// in order to be connected to, so someone who opens it, connects, and closes
/// it when they are done is never Idle inside that window. Every launch found
/// the release again, downloaded it again, and abandoned it again.
///
/// So these are about the bytes surviving the process that fetched them.
/// <see cref="UpdateServiceTests"/> still owns "never while a tunnel is up";
/// nothing here is allowed to weaken that.
/// </summary>
public class UpdateAcrossLaunchesTests
{
    private const string Installer = "Relay-Setup-x64.exe";
    private static readonly byte[] Payload = "pretend installer"u8.ToArray();

    private static string Sha => Convert.ToHexString(SHA256.HashData(Payload)).ToLowerInvariant();

    private const string NewerRelease = """
        {"tag_name":"v99.0.0","draft":false,"prerelease":false,
         "assets":[
           {"name":"Relay-Setup-x64.exe","browser_download_url":"https://x/Relay-Setup-x64.exe"},
           {"name":"SHA256SUMS.txt","browser_download_url":"https://x/SHA256SUMS.txt"}]}
        """;

    /// <summary>A release that is real and self-consistent: the listing matches the bytes.</summary>
    private sealed class Servable : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken token)
        {
            var url = request.RequestUri!.ToString();
            HttpContent content = url.EndsWith("SHA256SUMS.txt", StringComparison.Ordinal)
                ? new StringContent($"{Sha}  {Installer}")
                : url.Contains("api.github.com", StringComparison.Ordinal)
                    ? new StringContent(NewerRelease)
                    : new ByteArrayContent(Payload);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = content });
        }
    }

    private sealed class Notices
    {
        public readonly List<UpdateNotice> Kinds = [];
        public void Add(UpdateNotice kind, string version) => Kinds.Add(kind);
    }

    private static string Fresh()
    {
        var directory = Path.Combine(
            Path.GetTempPath(), "relay-launch-tests", Guid.NewGuid().ToString("n"));
        Directory.CreateDirectory(directory);
        return directory;
    }

    /// <summary>
    /// One run of the app. <paramref name="state"/> is what the app is doing;
    /// <paramref name="ran"/> collects the installers it would have started.
    /// </summary>
    private static UpdateService Launch(
        string directory, string state, List<string> ran, Notices notices, string version = "1.0.0") =>
        new(version, () => state, notices.Add,
            () => new UpdateCheck(version, new HttpClient(new Servable())),
            new UpdateInstaller(new HttpClient(new Servable())),
            idleWait: TimeSpan.Zero,
            downloadDirectory: directory,
            runInstaller: ran.Add);

    [Fact]
    public async Task AnUpdateFetchedWhileConnectedIsInstalledOnTheNextLaunch()
    {
        var directory = Fresh();
        var ran = new List<string>();
        var notices = new Notices();

        // Session one: the user is connected the whole time, which is the normal
        // way Relay is used. Nothing may install.
        await Launch(directory, "Connected", ran, notices).CheckAndMaybeInstallAsync();
        Assert.Empty(ran);

        // Session two: a fresh process, and nothing has been checked or fetched
        // in it. Before this existed, that meant starting from nothing.
        var installed = await Launch(directory, "Idle", ran, notices)
            .InstallPendingAsync(relaunch: true);

        Assert.True(installed, "the verified installer from the first session was not run");
        Assert.Single(ran);
        Assert.Equal(Path.Combine(directory, Installer), ran[0]);
        Assert.Contains(UpdateNotice.Installing, notices.Kinds);
    }

    [Fact]
    public async Task StillWillNotInstallWhileATunnelIsUp()
    {
        var directory = Fresh();
        var ran = new List<string>();

        await Launch(directory, "Connected", ran, new Notices()).CheckAndMaybeInstallAsync();

        // A second connected session must not take the new path as permission.
        // The installer stops Relay to replace it; doing that mid-call is the
        // one thing the whole idle rule exists to prevent.
        var installed = await Launch(directory, "Connected", ran, new Notices())
            .InstallPendingAsync(relaunch: true);

        Assert.False(installed);
        Assert.Empty(ran);
    }

    [Fact]
    public async Task ForgetsAPendingUpdateThisBuildAlreadyIs()
    {
        var directory = Fresh();
        var ran = new List<string>();

        // Fetched while connected, so it is pending...
        await Launch(directory, "Connected", ran, new Notices()).CheckAndMaybeInstallAsync();

        // ...and then it installed, so the next launch *is* 99.0.0. Without the
        // version check that launch would reinstall the build it is already
        // running, on every start, forever.
        var installed = await Launch(directory, "Idle", ran, new Notices(), version: "99.0.0")
            .InstallPendingAsync(relaunch: true);

        Assert.False(installed);
        Assert.Empty(ran);
        Assert.Null(PendingUpdate.Load(directory));
        Assert.False(File.Exists(Path.Combine(directory, Installer)));
    }

    [Fact]
    public async Task RefusesAPendingInstallerThatChangedOnDisk()
    {
        var directory = Fresh();
        var ran = new List<string>();
        var notices = new Notices();

        await Launch(directory, "Connected", ran, new Notices()).CheckAndMaybeInstallAsync();

        // It was verified when it was fetched. Since then it has sat in a
        // directory anything running as this user can write to, across a launch
        // and possibly a reboot — and what it does when run is replace the
        // program that carries the user's entire connection. Verifying once is
        // not the same as verifying.
        await File.WriteAllBytesAsync(Path.Combine(directory, Installer), "something else"u8.ToArray());

        var installed = await Launch(directory, "Idle", ran, notices).InstallPendingAsync(relaunch: true);

        Assert.False(installed);
        Assert.Empty(ran);
        Assert.Contains(UpdateNotice.Refused, notices.Kinds);
        Assert.Null(PendingUpdate.Load(directory));
    }

    [Fact]
    public async Task SaysNothingWhenTheDownloadDirectoryWasSweptAway()
    {
        var directory = Fresh();
        var ran = new List<string>();
        var notices = new Notices();

        await Launch(directory, "Connected", ran, new Notices()).CheckAndMaybeInstallAsync();
        File.Delete(Path.Combine(directory, Installer));

        var installed = await Launch(directory, "Idle", ran, notices).InstallPendingAsync(relaunch: true);

        // Windows is allowed to clean %TEMP%, and that is a feature rather than
        // a fault: the next check fetches it again. Nobody needs telling.
        Assert.False(installed);
        Assert.Empty(notices.Kinds);
    }

    [Fact]
    public async Task NothingPendingIsNotAnError()
    {
        var notices = new Notices();

        var installed = await Launch(Fresh(), "Idle", [], notices).InstallPendingAsync(relaunch: true);

        // The overwhelmingly common launch.
        Assert.False(installed);
        Assert.Empty(notices.Kinds);
    }

    [Fact]
    public void AnUpdateLandsOnTheAppThatIsRunning()
    {
        var arguments = UpdateInstaller.ArgumentsFor(@"E:\Programs\Relay", relaunch: true);

        // Inno remembers the previous install's folder in the registry and, with
        // /SILENT, accepts it with no dialog for anyone to correct. The laptop
        // this was found on still had InstallLocation=C:\RelayTest\ from a test
        // install whose folder had been deleted, while the real app lived
        // elsewhere and every shortcut pointed there. Without /DIR the update
        // would have installed flawlessly into a folder nobody launches from and
        // reported success.
        Assert.Contains(@"/DIR=""E:\Programs\Relay""", arguments);
        Assert.Contains("/SILENT", arguments);
    }

    [Fact]
    public void AnUpdateOnTheWayOutDoesNotReopenTheApp()
    {
        var onClose = UpdateInstaller.ArgumentsFor(@"C:\Relay", relaunch: false);
        var onStart = UpdateInstaller.ArgumentsFor(@"C:\Relay", relaunch: true);

        // Closing is the free moment to update, and it stays free only if the
        // app does not come back afterwards: the user asked it to close.
        Assert.DoesNotContain(UpdateInstaller.Relaunch, onClose);

        // And the start-up path must still relaunch, or the app the user just
        // opened vanishes, which reads as a crash.
        Assert.Contains(UpdateInstaller.Relaunch, onStart);
    }

    [Fact]
    public void TheFirstCheckHappensInsideAnOrdinarySession()
    {
        // It was two minutes, and plenty of sessions are shorter than that end
        // to end — open Relay, connect, finish, close. For those the check never
        // ran once, on any launch. The delay was never what keeps the launch
        // fast; the check has always been on a background thread.
        Assert.True(
            UpdateService.FirstCheckDelay <= TimeSpan.FromMinutes(1),
            $"first check waits {UpdateService.FirstCheckDelay}, longer than many whole sessions");
    }
}
