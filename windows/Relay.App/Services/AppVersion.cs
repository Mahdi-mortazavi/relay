using System.Reflection;

namespace Relay.App.Services;

/// <summary>
/// The one place the app answers "which build am I?".
///
/// This existed as an inline <c>Assembly.GetName().Version</c> read, and for
/// seven releases it answered "1.0.0" — the .NET SDK's default when nothing
/// stamps a version, because no csproj set one and the release workflow never
/// passed <c>/p:Version=</c>. Add/Remove Programs said 1.7.0, the installer
/// filename said 1.7.0, and the app's own diagnostic report — the only version
/// a Windows user ever sees — said 1.0.0. Every bug report arrived claiming a
/// version that did not exist, so no report could be matched to a build, and
/// two users comparing a stale download against a current one had no way to
/// discover that was what they were doing.
///
/// Read the informational version, not <see cref="AssemblyName.Version"/>:
/// AssemblyVersion is numeric-only, so a pre-release suffix ("1.8.0-rc1") would
/// be silently rounded off exactly when it matters most.
/// </summary>
public static class AppVersion
{
    /// <summary>e.g. "1.8.0". "unknown" only if the assembly carries no stamp at all.</summary>
    public static string Current { get; } = Read();

    private static string Read()
    {
        try
        {
            var informational = typeof(AppVersion).Assembly
                .GetCustomAttribute<AssemblyInformationalVersionAttribute>()
                ?.InformationalVersion;

            if (!string.IsNullOrWhiteSpace(informational))
            {
                // SourceLink appends "+<sha>"; that belongs in a build log, not
                // in front of a user pasting a report into an issue.
                var plus = informational.IndexOf('+');
                return plus < 0 ? informational : informational[..plus];
            }

            var version = typeof(AppVersion).Assembly.GetName().Version;
            return version is null ? "unknown" : $"{version.Major}.{version.Minor}.{version.Build}";
        }
        catch
        {
            return "unknown";
        }
    }
}
