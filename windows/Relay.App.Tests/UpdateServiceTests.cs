using System.Net;
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

    /// <summary>A release far newer than any build could be.</summary>
    private const string NewerRelease = """
        {"tag_name":"v99.0.0","draft":false,"prerelease":false,
         "assets":[
           {"name":"Relay-Setup-x64.exe","browser_download_url":"https://x/Relay-Setup-x64.exe"},
           {"name":"Relay-Setup-x86.exe","browser_download_url":"https://x/Relay-Setup-x86.exe"},
           {"name":"SHA256SUMS.txt","browser_download_url":"https://x/SHA256SUMS.txt"}]}
        """;

    /// <summary>A listing naming a different file, so nothing here can install.</summary>
    private const string Sums =
        "0000000000000000000000000000000000000000000000000000000000000000  other.exe";

    private static UpdateCheck Check() => new("1.0.0", new HttpClient(new Canned(NewerRelease)));

    /// <summary>Records what the person was told, without needing a tray.</summary>
    private sealed class Notices
    {
        public readonly List<UpdateNotice> Kinds = [];
        public void Add(UpdateNotice kind, string version) => Kinds.Add(kind);
    }

    /// <summary>A service whose tunnel is up, which is the interesting case.</summary>
    private static UpdateService Connected(Notices notices) =>
        new("1.0.0", () => "Connected", notices.Add, Check,
            new UpdateInstaller(new HttpClient(new Canned(Sums))));

    /// <summary>
    /// Already cancelled, so the wait-for-idle loop returns at once instead of
    /// holding the test for a poll interval. Every assertion here is about what
    /// happens before that wait.
    /// </summary>
    private static CancellationToken Cancelled()
    {
        var source = new CancellationTokenSource();
        source.Cancel();
        return source.Token;
    }

    [Fact]
    public async Task AnnouncesAnUpdateAsSoonAsItIsFound()
    {
        var notices = new Notices();

        await Connected(notices).CheckAndMaybeInstallAsync(Cancelled());

        // Announced even though a tunnel is up: knowing early costs nothing,
        // and only the install has to wait.
        Assert.Equal([UpdateNotice.Available], notices.Kinds);
    }

    [Fact]
    public async Task DoesNotAnnounceTheSameVersionTwice()
    {
        var notices = new Notices();
        var service = Connected(notices);

        await service.CheckAndMaybeInstallAsync(Cancelled());
        await service.CheckAndMaybeInstallAsync(Cancelled());

        // A daily check that re-notified every day would teach people to
        // dismiss it, which is how the one that matters gets missed.
        Assert.Single(notices.Kinds);
    }

    [Fact]
    public async Task NeverInstallsWhileATunnelIsUp()
    {
        var notices = new Notices();

        await Connected(notices).CheckAndMaybeInstallAsync(Cancelled());

        // The installer stops Relay in order to replace it. Doing that during a
        // call would drop the call.
        Assert.DoesNotContain(UpdateNotice.Installing, notices.Kinds);
    }

    [Fact]
    public async Task SaysNothingWhenThisBuildIsAlreadyCurrent()
    {
        var notices = new Notices();
        var current = """{"tag_name":"v1.0.0","draft":false,"prerelease":false,"assets":[]}""";
        var service = new UpdateService(
            "1.0.0", () => "Idle", notices.Add,
            () => new UpdateCheck("1.0.0", new HttpClient(new Canned(current))));

        await service.CheckAndMaybeInstallAsync(Cancelled());

        // Silence is the whole contract when there is nothing to say.
        Assert.Empty(notices.Kinds);
    }
}
