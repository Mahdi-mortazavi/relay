using System.Security.Cryptography;

namespace Relay.Core;

/// <summary>
/// Downloads a release's installer, checks it against the release's own
/// published hashes, and runs it.
///
/// The verification is not a nicety. Relay routes a phone's entire connection,
/// and an app that replaces itself unattended is an app whose update path is
/// worth attacking. Every release publishes SHA256SUMS.txt beside the
/// installers; this refuses to run anything whose hash is not in that file, and
/// refuses to install at all when a release did not publish one. A silent
/// update is only defensible when "silent" still means "exactly the bytes the
/// release published".
///
/// Kept free of UI types so it can be tested against a stubbed transport on a
/// machine that is not going to run an installer.
/// </summary>
public sealed class UpdateInstaller(HttpClient? http = null)
{
    /// <summary>What the caller needs to tell the user, and whether to bother.</summary>
    public enum Outcome
    {
        /// <summary>Downloaded, verified, and handed to the installer.</summary>
        Started,

        /// <summary>Could not reach it, or the download failed. Not news; try later.</summary>
        Unavailable,

        /// <summary>
        /// Downloaded, but the bytes are not the ones the release published.
        /// This is the case that must never be quietly retried or ignored.
        /// </summary>
        ChecksumMismatch,

        /// <summary>The release published no checksums, so nothing can be verified.</summary>
        Unverifiable,
    }

    private readonly HttpClient _http = Prepare(http);

    private static HttpClient Prepare(HttpClient? given)
    {
        var client = given ?? new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        if (!client.DefaultRequestHeaders.Contains("User-Agent"))
        {
            client.DefaultRequestHeaders.Add("User-Agent", "Relay-UpdateInstaller");
        }
        return client;
    }

    /// <summary>
    /// Fetches, verifies and launches the update. Returns before the installer
    /// finishes — it replaces this executable, so waiting for it from inside
    /// the process it is replacing is not a thing that can work.
    /// </summary>
    /// <param name="run">
    /// How to start the installer, injected so a test can assert what would
    /// have been run without running it.
    /// </param>
    public async Task<Outcome> InstallAsync(
        UpdateCheck.Available update,
        string? downloadDirectory = null,
        Action<string>? run = null,
        CancellationToken token = default)
    {
        var (outcome, path) = await DownloadAsync(update, downloadDirectory, token)
            .ConfigureAwait(false);
        return path is null ? outcome : Run(path, run);
    }

    /// <summary>
    /// Fetches and verifies the installer, and stops there.
    ///
    /// Separate from running it because the two have opposite constraints.
    /// Running it stops Relay, so it has to wait for a moment when nothing is
    /// connected. Downloading has to <em>not</em> wait for that — on the
    /// networks Relay is built for, the tunnel is often the only route to
    /// GitHub's release CDN in the first place, so "download only while
    /// disconnected" means "download only while it is unreachable", and the
    /// update never arrives at all. Verified on a connection that reaches
    /// api.github.com fine and cannot reach the asset host at all.
    ///
    /// The cost is that the download can use the phone's data. Once per
    /// release, for an app that has no other way to stay current, that is the
    /// better of the two mistakes.
    /// </summary>
    /// <returns>
    /// The verified installer's path, or null with the reason it is not there.
    /// </returns>
    public async Task<(Outcome Outcome, string? Path)> DownloadAsync(
        UpdateCheck.Available update,
        string? downloadDirectory = null,
        CancellationToken token = default)
    {
        if (string.IsNullOrWhiteSpace(update.ChecksumsUrl)) return (Outcome.Unverifiable, null);

        var name = FileName(update.Url);
        if (!name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) return (Outcome.Unavailable, null);

        var directory = downloadDirectory ?? Path.Combine(Path.GetTempPath(), "Relay-update");
        var target = Path.Combine(directory, name);
        // Downloaded under a name nothing will run, then renamed once the hash
        // matches. A rejected or half-finished download must never be left on
        // disk as something a person could double-click by mistake.
        var partial = target + ".part";

        string expected;
        string actual;
        try
        {
            Directory.CreateDirectory(directory);
            var sums = await _http.GetStringAsync(update.ChecksumsUrl, token).ConfigureAwait(false);
            var found = HashFor(sums, name);
            if (found is null) return (Outcome.Unverifiable, null);
            expected = found;

            actual = await DownloadAndHashAsync(update.Url, partial, token).ConfigureAwait(false);
        }
        catch (Exception)
        {
            Discard(partial);
            return (Outcome.Unavailable, null);
        }

        if (!CryptographicOperations.FixedTimeEquals(
                Convert.FromHexString(actual), Convert.FromHexString(expected)))
        {
            Discard(partial);
            return (Outcome.ChecksumMismatch, null);
        }

        try
        {
            File.Move(partial, target, overwrite: true);
        }
        catch (Exception)
        {
            Discard(partial);
            return (Outcome.Unavailable, null);
        }

        return (Outcome.Started, target);
    }

    /// <summary>Runs an installer that <see cref="DownloadAsync"/> already verified.</summary>
    public Outcome Run(string path, Action<string>? run = null)
    {
        try
        {
            (run ?? Launch)(path);
            return Outcome.Started;
        }
        catch (Exception)
        {
            return Outcome.Unavailable;
        }
    }

    /// <summary>
    /// Streams the installer to <paramref name="path"/>, returning its SHA-256.
    ///
    /// Streamed rather than fetched with GetByteArrayAsync, which is how this
    /// was first written, for two reasons that only showed up on a real
    /// machine. It held the whole installer -- around fifty megabytes -- in
    /// memory. And <see cref="HttpClient.Timeout"/> covers a buffered request
    /// end to end, so that one deadline had to cover the entire transfer: on a
    /// slow link it simply expired, and because every failure on this path is
    /// deliberately silent, the update was abandoned and not retried for
    /// another day. Every day. Forever.
    ///
    /// ResponseHeadersRead scopes the client's timeout to getting the headers,
    /// which is the part that should have a deadline. How long the body takes
    /// is the network's business, and the caller's token is what stops it.
    /// </summary>
    private async Task<string> DownloadAndHashAsync(
        string url, string path, CancellationToken token)
    {
        using var response = await _http.GetAsync(
            url, HttpCompletionOption.ResponseHeadersRead, token).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();

        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        await using var body = await response.Content.ReadAsStreamAsync(token).ConfigureAwait(false);
        await using var file = new FileStream(
            path, FileMode.Create, FileAccess.Write, FileShare.None,
            bufferSize: 128 * 1024, useAsync: true);

        var buffer = new byte[128 * 1024];
        int read;
        while ((read = await body.ReadAsync(buffer, token).ConfigureAwait(false)) > 0)
        {
            hash.AppendData(buffer, 0, read);
            await file.WriteAsync(buffer.AsMemory(0, read), token).ConfigureAwait(false);
        }

        return Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
    }

    /// <summary>Removes a download that must not survive. Never throws.</summary>
    private static void Discard(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch (Exception) { }
    }

    /// <summary>
    /// Runs the installer in the same per-user, no-prompt way the first install
    /// ran, and does not wait.
    ///
    /// This does <em>not</em> stop Relay, which an earlier comment here claimed
    /// it did. AppMutex makes Setup <em>ask</em> for the app to be closed — a
    /// modal message box that /SILENT does not suppress — so an update left to
    /// itself would have sat on a dialog nobody was there to answer. The caller
    /// has to leave, and <see cref="Relaunch"/> is how it gets to come back.
    /// </summary>
    private static void Launch(string installer) =>
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(installer)
        {
            // Not /VERYSILENT: a person who did not press anything should still
            // see that something is installing. /SILENT shows progress and no
            // questions, which is the honest middle.
            Arguments = $"/SILENT /NORESTART {Relaunch}",
            UseShellExecute = true,
        });

    /// <summary>
    /// Asks Setup to start Relay again when it is done.
    ///
    /// The installer's ordinary post-install launch carries Inno's
    /// <c>skipifsilent</c> flag, which is right for someone who chose a silent
    /// install from a command line and wrong for an update that closed the app
    /// on its own — that path would have left the tray empty and the app gone,
    /// which reads as a crash, not an update. The .iss has a second entry
    /// keyed on this parameter.
    /// </summary>
    public const string Relaunch = "/relaunch=1";

    /// <summary>
    /// Pulls one file's hash out of a sha256sum-format listing:
    /// <c>&lt;64 hex&gt;␣␣&lt;name&gt;</c>, one per line.
    ///
    /// Matches on the file name only, so a listing that records paths
    /// ("./Relay-Setup-x64.exe") still resolves. Public because parsing is
    /// where this goes wrong and the tests live in another assembly.
    /// </summary>
    public static string? HashFor(string checksums, string fileName)
    {
        foreach (var line in checksums.Split('\n'))
        {
            var trimmed = line.Trim();
            if (trimmed.Length < 66) continue;

            var hash = trimmed[..64];
            if (!IsHex(hash)) continue;

            var rest = trimmed[64..].TrimStart(' ', '*', '\t');
            if (Path.GetFileName(rest).Equals(fileName, StringComparison.OrdinalIgnoreCase))
            {
                return hash.ToLowerInvariant();
            }
        }
        return null;
    }

    private static bool IsHex(ReadOnlySpan<char> text)
    {
        foreach (var c in text)
        {
            var hex = c is >= '0' and <= '9' or >= 'a' and <= 'f' or >= 'A' and <= 'F';
            if (!hex) return false;
        }
        return true;
    }

    /// <summary>
    /// The last path segment of a download URL, with any query dropped. Refuses
    /// anything with a separator in it so a hostile URL cannot steer the write
    /// out of the download directory.
    /// </summary>
    public static string FileName(string url)
    {
        var text = url.Split('?')[0].Split('#')[0];
        var slash = text.LastIndexOf('/');
        var name = slash >= 0 ? text[(slash + 1)..] : text;
        return name.Contains('\\') || name.Contains("..") ? string.Empty : name;
    }
}
