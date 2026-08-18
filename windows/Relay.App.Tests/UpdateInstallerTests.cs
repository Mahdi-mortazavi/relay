using System.Net;
using System.Security.Cryptography;
using System.Text;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// The update path, which replaces the running application unattended.
///
/// Everything here is about the refusals. A silent update that installs the
/// wrong bytes is worse than no silent update at all, and every one of these
/// cases is a way that could happen quietly.
/// </summary>
public class UpdateInstallerTests
{
    private const string Installer = "Relay-Setup-x64.exe";

    private static string Sums(params (string name, byte[] content)[] files) =>
        string.Join('\n', files.Select(f =>
            $"{Convert.ToHexString(SHA256.HashData(f.content)).ToLowerInvariant()}  {f.name}"));

    /// <summary>Serves a fixed body per URL, so nothing here touches the network.</summary>
    private sealed class Canned(Dictionary<string, byte[]> routes) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken token)
        {
            var url = request.RequestUri!.ToString();
            if (!routes.TryGetValue(url, out var body))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent(body),
            });
        }
    }

    private static (UpdateInstaller, string dir, List<string> ran) Build(
        byte[] served, string sums)
    {
        var routes = new Dictionary<string, byte[]>
        {
            ["https://x/" + Installer] = served,
            ["https://x/SHA256SUMS.txt"] = Encoding.UTF8.GetBytes(sums),
        };
        var dir = Path.Combine(Path.GetTempPath(), "relay-test-" + Guid.NewGuid().ToString("N"));
        return (new UpdateInstaller(new HttpClient(new Canned(routes))), dir, []);
    }

    private static UpdateCheck.Available Update(string? sumsUrl = "https://x/SHA256SUMS.txt") =>
        new("9.9.9", "https://x/" + Installer, null, sumsUrl);

    [Fact]
    public async Task RunsTheInstallerWhenTheHashMatchesWhatTheReleasePublished()
    {
        var payload = Encoding.UTF8.GetBytes("pretend installer");
        var (installer, dir, ran) = Build(payload, Sums((Installer, payload)));

        var outcome = await installer.InstallAsync(Update(), dir, ran.Add);

        Assert.Equal(UpdateInstaller.Outcome.Started, outcome);
        Assert.Equal(Path.Combine(dir, Installer), Assert.Single(ran));
        Directory.Delete(dir, recursive: true);
    }

    [Fact]
    public async Task RefusesToRunBytesThatDoNotMatchTheChecksum()
    {
        // The listing describes one file; the server serves another. This is the
        // shape of every interesting attack on an unattended updater.
        var (installer, dir, ran) = Build(
            Encoding.UTF8.GetBytes("something else entirely"),
            Sums((Installer, Encoding.UTF8.GetBytes("the real installer"))));

        var outcome = await installer.InstallAsync(Update(), dir, ran.Add);

        Assert.Equal(UpdateInstaller.Outcome.ChecksumMismatch, outcome);
        Assert.Empty(ran);
        // And it must not be sitting on disk waiting to be double-clicked.
        Assert.False(File.Exists(Path.Combine(dir, Installer)));
    }

    [Fact]
    public async Task RefusesWhenTheReleasePublishedNoChecksumsAtAll()
    {
        var payload = Encoding.UTF8.GetBytes("pretend installer");
        var (installer, dir, ran) = Build(payload, Sums((Installer, payload)));

        var outcome = await installer.InstallAsync(Update(sumsUrl: null), dir, ran.Add);

        Assert.Equal(UpdateInstaller.Outcome.Unverifiable, outcome);
        Assert.Empty(ran);
    }

    [Fact]
    public async Task RefusesWhenTheChecksumListingDoesNotMentionThisFile()
    {
        var payload = Encoding.UTF8.GetBytes("pretend installer");
        var (installer, dir, ran) = Build(payload, Sums(("Relay-Setup-x86.exe", payload)));

        var outcome = await installer.InstallAsync(Update(), dir, ran.Add);

        Assert.Equal(UpdateInstaller.Outcome.Unverifiable, outcome);
        Assert.Empty(ran);
    }

    [Theory]
    // The real release publishes plain "name"; sha256sum -b writes "*name";
    // some tools write a path. All three have to resolve to the same file.
    [InlineData("abc  Relay-Setup-x64.exe")]
    [InlineData("abc *Relay-Setup-x64.exe")]
    [InlineData("abc  ./Relay-Setup-x64.exe")]
    public void ReadsEveryShapeOfChecksumLineTheToolsProduce(string line)
    {
        var hash = new string('a', 64);
        Assert.Equal(hash, UpdateInstaller.HashFor(line.Replace("abc", hash), Installer));
    }

    [Fact]
    public void IgnoresLinesThatAreNotAHashAndAName()
    {
        var listing = "# a comment\n\nnot-a-hash  Relay-Setup-x64.exe\n";
        Assert.Null(UpdateInstaller.HashFor(listing, Installer));
    }

    [Theory]
    [InlineData("https://x/Relay-Setup-x64.exe", "Relay-Setup-x64.exe")]
    [InlineData("https://x/Relay-Setup-x64.exe?token=1", "Relay-Setup-x64.exe")]
    // A URL is attacker-influenced input the moment anything but GitHub answers.
    // Neither of these may become a path the download escapes into.
    [InlineData("https://x/..\\..\\evil.exe", "")]
    [InlineData("https://x/../../evil.exe", "")]
    public void NeverLetsADownloadNameEscapeItsDirectory(string url, string expected)
    {
        Assert.Equal(expected, UpdateInstaller.FileName(url));
    }
}
