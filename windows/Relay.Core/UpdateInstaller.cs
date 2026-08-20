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
        if (string.IsNullOrWhiteSpace(update.ChecksumsUrl)) return Outcome.Unverifiable;

        var name = FileName(update.Url);
        if (!name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) return Outcome.Unavailable;

        var directory = downloadDirectory ?? Path.Combine(Path.GetTempPath(), "Relay-update");
        var target = Path.Combine(directory, name);

        string expected;
        byte[] payload;
        try
        {
            Directory.CreateDirectory(directory);
            var sums = await _http.GetStringAsync(update.ChecksumsUrl, token).ConfigureAwait(false);
            var found = HashFor(sums, name);
            if (found is null) return Outcome.Unverifiable;
            expected = found;

            payload = await _http.GetByteArrayAsync(update.Url, token).ConfigureAwait(false);
        }
        catch (Exception)
        {
            return Outcome.Unavailable;
        }

        var actual = Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant();
        if (!CryptographicOperations.FixedTimeEquals(
                Convert.FromHexString(actual), Convert.FromHexString(expected)))
        {
            return Outcome.ChecksumMismatch;
        }

        try
        {
            // Written only after the hash matches. A rejected download never
            // reaches disk as something a person could later double-click by
            // mistake.
            await File.WriteAllBytesAsync(target, payload, token).ConfigureAwait(false);
        }
        catch (Exception)
        {
            return Outcome.Unavailable;
        }

        try
        {
            (run ?? Launch)(target);
            return Outcome.Started;
        }
        catch (Exception)
        {
            return Outcome.Unavailable;
        }
    }

    /// <summary>
    /// Runs the installer in the same per-user, no-prompt way the first install
    /// ran, and does not wait: it stops Relay (AppMutex in the .iss) and
    /// replaces this executable underneath us.
    /// </summary>
    private static void Launch(string installer) =>
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(installer)
        {
            // Not /VERYSILENT: a person who did not press anything should still
            // see that something is installing. /SILENT shows progress and no
            // questions, which is the honest middle.
            Arguments = "/SILENT /NORESTART",
            UseShellExecute = true,
        });

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
