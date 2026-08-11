using System.Net.Http.Json;
using System.Text.Json.Serialization;

namespace Relay.Core;

/// <summary>
/// Asks GitHub whether a newer release exists.
///
/// Only asks. Downloading and running an installer is a separate, deliberate
/// step the person takes, because an app that silently replaces itself is an
/// app that can silently replace itself with something else — and this one
/// routes a phone's traffic.
/// </summary>
public sealed class UpdateCheck
{
    private const string LatestUrl = "https://api.github.com/repos/Mahdi-mortazavi/relay/releases/latest";

    public sealed record Available(string Version, string Url, string? Notes);

    private readonly HttpClient _http;
    private readonly string _currentVersion;

    public UpdateCheck(string currentVersion, HttpClient? http = null)
    {
        _currentVersion = currentVersion;
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
        if (!_http.DefaultRequestHeaders.Contains("User-Agent"))
        {
            // GitHub refuses requests without one, with a 403 that looks like a
            // permissions problem rather than a missing header.
            _http.DefaultRequestHeaders.Add("User-Agent", "Relay-UpdateCheck");
        }
    }

    /// <summary>
    /// Returns the newer release, or null when this build is current — or when
    /// the check could not be made at all. A failed check is not news: someone
    /// on a plane should not be told their app is broken because GitHub was
    /// unreachable.
    /// </summary>
    public async Task<Available?> CheckAsync(CancellationToken token = default)
    {
        try
        {
            var release = await _http.GetFromJsonAsync<GitHubRelease>(LatestUrl, token).ConfigureAwait(false);
            if (release?.TagName is null) return null;
            if (release.Draft || release.Prerelease) return null;

            var latest = Parse(release.TagName);
            var current = Parse(_currentVersion);
            if (latest is null || current is null) return null;
            if (Compare(latest, current) <= 0) return null;

            var asset = release.Assets?.FirstOrDefault(a =>
                a.Name is not null &&
                a.Name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) &&
                a.Name.Contains(Environment.Is64BitOperatingSystem ? "x64" : "x86", StringComparison.OrdinalIgnoreCase));

            return new Available(
                release.TagName.TrimStart('v'),
                asset?.BrowserDownloadUrl ?? release.HtmlUrl ?? LatestUrl,
                release.Body);
        }
        catch (Exception)
        {
            // Offline, rate-limited, DNS-poisoned, whatever. None of it is
            // something to interrupt someone about.
            return null;
        }
    }

    /// <summary>
    /// Parses "1.3.1" or "v1.3.1" into comparable parts. Returns null for
    /// anything else rather than guessing: a misparse here would either offer a
    /// downgrade or hide a real update.
    ///
    /// Public, like the beacon parser, because it is pure, it is where this
    /// feature goes wrong, and tests live in a separate assembly.
    /// </summary>
    public static int[]? Parse(string? version)
    {
        if (string.IsNullOrWhiteSpace(version)) return null;
        var text = version.Trim().TrimStart('v', 'V');
        // Drop any pre-release suffix: 1.4.0-rc1 compares as 1.4.0.
        var dash = text.IndexOf('-');
        if (dash >= 0) text = text[..dash];
        var parts = text.Split('.');
        if (parts.Length is < 2 or > 4) return null;
        var numbers = new int[parts.Length];
        for (var i = 0; i < parts.Length; i++)
        {
            if (!int.TryParse(parts[i], out numbers[i]) || numbers[i] < 0) return null;
        }
        return numbers;
    }

    /// <summary>Positive when <paramref name="a"/> is newer.</summary>
    public static int Compare(int[] a, int[] b)
    {
        for (var i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            var left = i < a.Length ? a[i] : 0;
            var right = i < b.Length ? b[i] : 0;
            if (left != right) return left.CompareTo(right);
        }
        return 0;
    }

    private sealed class GitHubRelease
    {
        [JsonPropertyName("tag_name")] public string? TagName { get; set; }
        [JsonPropertyName("html_url")] public string? HtmlUrl { get; set; }
        [JsonPropertyName("body")] public string? Body { get; set; }
        [JsonPropertyName("draft")] public bool Draft { get; set; }
        [JsonPropertyName("prerelease")] public bool Prerelease { get; set; }
        [JsonPropertyName("assets")] public List<GitHubAsset>? Assets { get; set; }
    }

    private sealed class GitHubAsset
    {
        [JsonPropertyName("name")] public string? Name { get; set; }
        [JsonPropertyName("browser_download_url")] public string? BrowserDownloadUrl { get; set; }
    }
}
