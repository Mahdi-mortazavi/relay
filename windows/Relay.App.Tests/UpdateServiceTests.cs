using System.Net;
using System.Security.Cryptography;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// When the updater is allowed to act.
///
/// The install replaces the running application, so the rule that matters most
/// is that it never happens while a tunnel is up: nobody mid-call wants their
/// connection dropped because a release landed. Whether the bytes are
/// trustworthy is <see cref="UpdateInstallerTests"/>'s question, not this one's.
/// </summary>
public class UpdateServiceTests
{
    private sealed class Canned(string body) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken token) =>
            Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(body),
            });
    }

    /// <summary>
    /// Serves a real, self-consistent release: a checksum listing that matches
    /// the installer bytes it also serves.
    ///
    /// Needed because the download now happens before the wait for idle. With a
    /// listing that named some other file, "did not install while connected"
    /// would pass because the download was refused, which proves nothing about
    /// the rule under test.
    /// </summary>
    private sealed class Servable : HttpMessageHandler
    {
        private static readonly byte[] Payload = "pretend installer"u8.ToArray();

        private static string Listing =>
            Convert.ToHexString(SHA256.HashData(Payload)).ToLowerInvariant()
            + "  Relay-Setup-x64.exe";

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken token)
        {
            var url = request.RequestUri!.ToString();
            HttpContent content = url.EndsWith("SHA256SUMS.txt", StringComparison.Ordinal)
                ? new StringContent(Listing)
                : url.Contains("api.github.com", StringComparison.Ordinal)
                    ? new StringContent(NewerRelease)
                    : new ByteArrayContent(Payload);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = content });
        }
    }

    /// <summary>
    /// A transport that fails the way an unreachable one does.
    ///
    /// Synchronously, and from the handler, because that is where the real
    /// failures come from — DNS, a refused connection, a body that is not the
    /// JSON it claims to be. What the failure *is* does not matter here; that
    /// it is distinguishable from "nothing new" is the whole point.
    /// </summary>
    private sealed class Offline : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken token) =>
            throw new HttpRequestException("no such host is known");
    }

    /// <summary>A release far newer than any build could be.</summary>
    private const string NewerRelease = """
        {"tag_name":"v99.0.0","draft":false,"prerelease":false,
         "assets":[
           {"name":"Relay-Setup-x64.exe","browser_download_url":"https://x/Relay-Setup-x64.exe"},
           {"name":"Relay-Setup-x86.exe","browser_download_url":"https://x/Relay-Setup-x86.exe"},
           {"name":"SHA256SUMS.txt","browser_download_url":"https://x/SHA256SUMS.txt"}]}
        """;

    private static UpdateCheck Check() => new("1.0.0", new HttpClient(new Canned(NewerRelease)));

    /// <summary>Records what the person was told, without needing a tray.</summary>
    private sealed class Notices
    {
        public readonly List<UpdateNotice> Kinds = [];
        public void Add(UpdateNotice kind, string version) => Kinds.Add(kind);
    }

    /// <summary>
    /// A service whose tunnel is up, which is the interesting case.
    ///
    /// The zero idle budget is what keeps this a millisecond test: the real one
    /// is a day of one-minute polls. It changes when the service gives up
    /// waiting, never whether it was allowed to install.
    /// </summary>
    private static UpdateService Connected(Notices notices) =>
        new("1.0.0", () => "Connected", notices.Add, Check,
            new UpdateInstaller(new HttpClient(new Servable())),
            idleWait: TimeSpan.Zero,
            downloadDirectory: Isolated());

    /// <summary>
    /// A download directory of this test's own.
    ///
    /// These used to share the app's real one. That was untidy and became
    /// dangerous the moment a verified download started leaving a note behind
    /// for the next launch to act on: a test run would have written a note
    /// claiming version 99.0.0, pointing at seventeen bytes reading "pretend
    /// installer", into the exact directory the installed Relay reads at
    /// start-up.
    /// </summary>
    private static string Isolated()
    {
        var directory = Path.Combine(
            Path.GetTempPath(), "relay-service-tests", Guid.NewGuid().ToString("n"));
        Directory.CreateDirectory(directory);
        return directory;
    }

    [Fact]
    public async Task AnnouncesAnUpdateAsSoonAsItIsFound()
    {
        var notices = new Notices();

        await Connected(notices).CheckAndMaybeInstallAsync();

        // Announced even though a tunnel is up: knowing early costs nothing,
        // and only the install has to wait.
        Assert.Equal([UpdateNotice.Available], notices.Kinds);
    }

    [Fact]
    public async Task DoesNotAnnounceTheSameVersionTwice()
    {
        var notices = new Notices();
        var service = Connected(notices);

        await service.CheckAndMaybeInstallAsync();
        await service.CheckAndMaybeInstallAsync();

        // A daily check that re-notified every day would teach people to
        // dismiss it, which is how the one that matters gets missed.
        Assert.Single(notices.Kinds);
    }

    [Fact]
    public async Task NeverInstallsWhileATunnelIsUp()
    {
        var notices = new Notices();

        await Connected(notices).CheckAndMaybeInstallAsync();

        // Announced, so we know the path ran and this is not passing on an
        // empty list — and then stopped, because the installer stops Relay in
        // order to replace it and doing that during a call would drop the call.
        //
        // The bytes were fetched and verified first: that is deliberate, and the
        // handler here serves a listing that genuinely matches, so nothing but
        // the connected state can be what stopped it.
        Assert.Contains(UpdateNotice.Available, notices.Kinds);
        Assert.DoesNotContain(UpdateNotice.Installing, notices.Kinds);
    }

    [Fact]
    public async Task FetchesTheUpdateEvenWhileTheTunnelIsUp()
    {
        // The one test that deliberately uses the app's real download
        // directory, because where the bytes land is what it is asserting.
        var directory = PendingUpdate.DefaultDirectory;
        var target = Path.Combine(directory, "Relay-Setup-x64.exe");
        if (File.Exists(target)) File.Delete(target);
        PendingUpdate.Clear(directory);

        await new UpdateService(
            "1.0.0", () => "Connected", new Notices().Add, Check,
            new UpdateInstaller(new HttpClient(new Servable())),
            idleWait: TimeSpan.Zero).CheckAndMaybeInstallAsync();

        // The reason this matters is not speed. Relay is used where the tunnel
        // is the only working route to GitHub's release CDN, so waiting for
        // "disconnected" before downloading meant waiting for the one state in
        // which the bytes cannot be reached — and the update never landed at
        // all. Installing still waits; fetching must not.
        Assert.True(File.Exists(target));

        // Both halves: the note left here would otherwise tell an installed
        // Relay, on its next launch, that "pretend installer" is version 99.0.0.
        File.Delete(target);
        PendingUpdate.Clear(directory);
    }

    [Fact]
    public async Task SaysNothingWhenThisBuildIsAlreadyCurrent()
    {
        var notices = new Notices();
        var current = """{"tag_name":"v1.0.0","draft":false,"prerelease":false,"assets":[]}""";
        var service = new UpdateService(
            "1.0.0", () => "Idle", notices.Add,
            () => new UpdateCheck("1.0.0", new HttpClient(new Canned(current))),
            idleWait: TimeSpan.Zero);

        var cycle = await service.CheckAndMaybeInstallAsync();

        // Silence is the whole contract when there is nothing to say.
        Assert.Empty(notices.Kinds);

        // And this is the half that keeps the failure test below honest: a
        // check that completed says so, whatever it found.
        Assert.Equal(UpdateCycle.Checked, cycle);
    }

    [Fact]
    public async Task AFailedCheckIsNeitherSilentNorMistakenForNothingToDo()
    {
        var notices = new Notices();
        var log = new List<string>();

        var cycle = await new UpdateService(
            "1.0.0", () => "Idle", notices.Add,
            () => new UpdateCheck("1.0.0", new HttpClient(new Offline())),
            idleWait: TimeSpan.Zero,
            downloadDirectory: Isolated(),
            log: log.Add).CheckAndMaybeInstallAsync();

        // "I could not find out" used to arrive here as "there is nothing
        // new" — the check swallowed its own exception and returned null, the
        // same null a current build produces. The loop then waited a full day
        // on a tray app that stays open, so one transient failure cost that
        // launch its update entirely.
        Assert.Equal(UpdateCycle.Failed, cycle);

        // The rule that does not move: a failed check still tells the person
        // nothing and interrupts nothing.
        Assert.Empty(notices.Kinds);

        // But it is no longer invisible. This was observed on hardware with
        // 2.8.5 installed and 2.8.6 published, and the cause could not be
        // established afterwards because there was nothing written down at all.
        Assert.Contains(log, line => line.StartsWith("Update check failed", StringComparison.Ordinal));
    }

    [Fact]
    public void AFailedCheckIsTriedAgainInMinutesNotTomorrow()
    {
        // A day is the cadence for "nothing new", not for "no answer".
        Assert.Equal(UpdateService.Interval, UpdateService.NextDelay(0));
        Assert.True(
            UpdateService.NextDelay(1) <= TimeSpan.FromMinutes(10),
            $"a failed check waits {UpdateService.NextDelay(1)} before trying again");

        // It still backs off: a laptop that is offline all week must not ask
        // every five minutes for a week.
        Assert.True(UpdateService.NextDelay(5) > UpdateService.NextDelay(1));

        // And it stops at the ordinary interval instead of overflowing. The
        // counter climbs for as long as the app is open, which on this app is
        // measured in weeks.
        Assert.Equal(UpdateService.Interval, UpdateService.NextDelay(int.MaxValue));
    }
}
