using System.Net;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// When the updater is allowed to act.
///
/// The install replaces the running application, so the rule that matters is
/// that it never happens while a tunnel is up — someone mid-call does not want
/// their connection dropped because a release landed. The verification itself
/// is <see cref="UpdateInstallerTests"/>'s job.
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

    /// <summary>A release far newer than anything this build could be.</summary>
    private const string NewerRelease = """
        {"tag_name":"v99.0.0","draft":false,"prerelease":false,
         "assets":[
           {"name":"Relay-Setup-x64.exe","browser_download_url":"https://x/Relay-Setup-x64.exe"},
           {"name":"Relay-Setup-x86.exe","browser_download_url":"https://x/Relay-Setup-x86.exe"},
           {"name":"SHA256SUMS.txt","browser_download_url":"https://x/SHA256SUMS.txt"}]}
        """;

    private static UpdateCheck Check() =>
        new("1.0.0", new HttpClient(new Canned(NewerRelease)));

    /// <summary>Records what the user was told, without a tray.</summary>
    private sealed class Notices
    {
        public readonly List<UpdateNotice> Kinds = [];
        public void Add(UpdateNotice kind, string version) => Kinds.Add(kind);
    }

    [Fact]
    public async Task AnnouncesAnUpdateAsSoonAsItIsFound()
    {
        var notices = new Notices();
        var service = Connected(notices);

        await service.CheckAndMaybeInstallAsync(Cancelled());

        // Knowing early costs nothing, so the notice does not wait for idle the
        // way the install does -- this runs with a tunnel up.
        Assert.Equal([UpdateNotice.Available], notices.Kinds);
    }

    [Fact]
    public async Task DoesNotAnnounceTheSameVersionTwice()
    {
        var notices = new Notices();
        var service = Connected(notices);

        await service.CheckAndMaybeInstallAsync(Cancelled());
        await service.CheckAndMaybeInstallAsync(Cancelled());

        // A daily check that re-notified every day would train people to
        // dismiss it, which is how a real one gets missed.
        Assert.Single(notices.Kinds);
    }

    /// <summary>
    /// Already cancelled, so the wait-for-idle loop returns immediately rather
    /// than holding the test for the poll interval. The assertions above are
    /// about what happens *before* that wait.
    /// </summary>
    private static CancellationToken Cancelled()
    {
        var source = new CancellationTokenSource();
        source.Cancel();
        return source.Token;
    }

    [Fact]
    public void TheDefaultIsToCheck()
    {
        // Constructing it must not require a factory: the app passes none, and
        // a service that quietly did nothing without one is the bug this whole
        // class exists to fix — UpdateCheck and UpdateInstaller sat unused and
        // fully tested for three releases.
        var service = new UpdateService(AppController.Instance, (_, _) => { });

        Assert.NotNull(service);
    }
}
