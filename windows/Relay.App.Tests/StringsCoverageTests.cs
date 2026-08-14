using System.Text.RegularExpressions;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// Every string the window asks for must exist in both languages.
///
/// <see cref="Relay.App.Strings"/> falls back to the key itself when it has no
/// entry, which is the right behaviour at runtime — a missing word must never
/// be able to fault an unpackaged window — and a silent one. Six keys shipped
/// missing: someone who typed a code no phone was answering was told
/// "CodeNoDevice", and the error surface underneath it said "ErrCodeNotFound".
/// Nothing crashed, no test failed, and the app spoke to the user in
/// identifiers.
///
/// This reads the sources rather than calling the class: Relay.App is a WinUI
/// application project and cannot be referenced from a plain test assembly, so
/// the choice is a source-level check or no check at all.
/// </summary>
public class StringsCoverageTests
{
    private static readonly string AppDir =
        Path.Combine(SharedContracts.RepoRoot, "windows", "Relay.App");

    private static readonly string StringsSource =
        File.ReadAllText(Path.Combine(AppDir, "Strings.cs"));

    /// <summary>Keys passed to <c>Strings.Get("…")</c> anywhere in the app.</summary>
    private static IEnumerable<string> RequestedKeys()
    {
        foreach (var file in Directory.EnumerateFiles(AppDir, "*.cs", SearchOption.AllDirectories))
        {
            var source = File.ReadAllText(file);
            foreach (Match m in Regex.Matches(source, """Strings\.Get\("([A-Za-z0-9_]+)"\)"""))
            {
                yield return m.Groups[1].Value;
            }

            // ApplyError names its title, body and button keys in a switch arm
            // rather than at a Get call, so they are invisible to the rule
            // above — and they are exactly the keys that shipped missing.
            // Anchored on the error code so this can only ever read that one
            // switch, default arm included.
            foreach (Match m in Regex.Matches(
                         source,
                         """(?:"ERR_[A-Z0-9_]+"|^\s*_)\s*=>\s*\(\s*(?:\(string\??\))?"([A-Za-z0-9_]+)",\s*(?:\(string\??\))?"([A-Za-z0-9_]+)",\s*(?:\(string\??\))?(?:"([A-Za-z0-9_]+)"|null)\s*\)""",
                         RegexOptions.Multiline))
            {
                yield return m.Groups[1].Value;
                yield return m.Groups[2].Value;
                if (m.Groups[3].Success) yield return m.Groups[3].Value;
            }
        }
    }

    /// <summary>The keys defined in one of the two dictionaries in Strings.cs.</summary>
    private static HashSet<string> DefinedIn(string language)
    {
        var start = StringsSource.IndexOf($"Dictionary<string, string> {language} = new()", StringComparison.Ordinal);
        Assert.True(start >= 0, $"Strings.cs has no {language} dictionary — this test is reading the wrong shape.");

        var end = StringsSource.IndexOf("\n    };", start, StringComparison.Ordinal);
        Assert.True(end > start, $"Could not find the end of the {language} dictionary.");

        var body = StringsSource[start..end];
        return Regex.Matches(body, """\["([A-Za-z0-9_]+)"\]\s*=""")
            .Select(m => m.Groups[1].Value)
            .ToHashSet();
    }

    [Fact]
    public void The_app_only_asks_for_strings_that_exist()
    {
        var requested = RequestedKeys().ToHashSet();
        Assert.NotEmpty(requested);

        var english = DefinedIn("En");
        var persian = DefinedIn("Fa");

        var missingEnglish = requested.Except(english).OrderBy(k => k).ToList();
        var missingPersian = requested.Except(persian).OrderBy(k => k).ToList();

        Assert.True(
            missingEnglish.Count == 0,
            "These keys are used but have no English text, so the user is shown the key:\n  " +
            string.Join("\n  ", missingEnglish));
        Assert.True(
            missingPersian.Count == 0,
            "These keys are used but have no Persian text, so a Persian Windows falls back to English:\n  " +
            string.Join("\n  ", missingPersian));
    }

    [Fact]
    public void The_two_languages_define_the_same_keys()
    {
        // A key present in only one dictionary is a half-translated app, and
        // Persian is not a second-class language here — the project ships to
        // people who read it first.
        var english = DefinedIn("En");
        var persian = DefinedIn("Fa");

        Assert.True(
            english.SetEquals(persian),
            "English-only: " + string.Join(", ", english.Except(persian).OrderBy(k => k)) +
            "\nPersian-only: " + string.Join(", ", persian.Except(english).OrderBy(k => k)));
    }

    [Fact]
    public void No_string_still_tells_the_user_to_look_for_an_eight_character_code()
    {
        // The phone shows two digits. A caption asking for eight characters in
        // front of it is not a smaller problem than a crash: it is read as
        // "these two apps are not the same product", and people stop there.
        // See /shared/pairing-beacon.md → Compatibility.
        var offenders = new List<string>();
        foreach (Match m in Regex.Matches(StringsSource, """\["([A-Za-z0-9_]+)"\]\s*=\s*"([^"]*)","""))
        {
            var key = m.Groups[1].Value;
            var text = m.Groups[2].Value;
            // CodeHintLong and the long-code hints are the fallback path and
            // are allowed to say eight — they are only ever shown after the
            // user says their phone shows a longer code.
            if (key is "CodeHintLong" or "CodeIncomplete" or "CodeBadChars" or "CodeChecksum") continue;
            if (text.Contains("8-character") || text.Contains("8 characters") ||
                text.Contains("۸ حرف") || text.Contains("8 حرف"))
            {
                offenders.Add($"{key}: {text}");
            }
        }

        Assert.True(offenders.Count == 0,
            "These strings still ask for the retired 8-character code:\n  " + string.Join("\n  ", offenders));
    }
}
