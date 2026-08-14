using System.Text.RegularExpressions;
using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// The PC must ask for the code the phone is showing.
///
/// It has not, twice. The phone led with a two-digit code from v1.4.0 while the
/// PC's box still demanded eight characters, and a user holding both apps
/// reported it the only way the symptom allows: "these versions have different
/// code, they don't work together". Nothing failed, because no test compared
/// the two ends — one is XAML in a WinUI project, the other is a constant in
/// Core, and nothing had ever read them in the same breath.
///
/// This reads the markup, for the same reason <see cref="StringsCoverageTests"/>
/// does: Relay.App cannot be referenced from a plain test assembly, so it is a
/// source-level check or no check at all.
/// </summary>
public class CodeEntryParityTests
{
    private static readonly string Xaml =
        File.ReadAllText(Path.Combine(
            SharedContracts.RepoRoot, "windows", "Relay.App", "MainWindow.xaml"));

    private static string CodeBoxAttribute(string name)
    {
        var box = Regex.Match(Xaml, """<TextBox x:Name="CodeBox".*?/>""", RegexOptions.Singleline);
        Assert.True(box.Success, "CodeBox is gone from MainWindow.xaml — this test needs updating.");

        var attribute = Regex.Match(box.Value, $"""{name}="([^"]*)\"""");
        Assert.True(attribute.Success, $"CodeBox has no {name}.");
        return attribute.Groups[1].Value;
    }

    /// <summary>
    /// The box accepts exactly as many characters as the phone prints. Larger
    /// would be harmless; smaller silently truncates; and eight, which is what
    /// shipped, rejects everything a person can type off the phone's screen.
    /// </summary>
    [Fact]
    public void The_code_box_is_sized_for_the_code_the_phone_shows()
    {
        Assert.Equal(LanDiscovery.CodeLength, int.Parse(CodeBoxAttribute("MaxLength")));
    }

    /// <summary>
    /// And the placeholder shows the right shape. "XXXX-XXXX" in front of a
    /// phone displaying "42" is what made two apps look incompatible.
    /// </summary>
    [Fact]
    public void The_placeholder_has_the_shape_of_a_short_code()
    {
        var placeholder = CodeBoxAttribute("PlaceholderText");

        Assert.Equal(LanDiscovery.CodeLength, placeholder.Length);
        Assert.All(placeholder, c => Assert.InRange(c, '0', '9'));
    }

    /// <summary>
    /// The short code is the default way in. A build that opened straight into
    /// the eight-character mode would reproduce the original report exactly.
    /// </summary>
    [Fact]
    public void Short_mode_is_what_the_panel_opens_in()
    {
        var source = File.ReadAllText(Path.Combine(
            SharedContracts.RepoRoot, "windows", "Relay.App", "MainWindow.xaml.cs"));

        var enterCode = Regex.Match(
            source,
            """private void OnEnterCodeClick.*?\n    \}""",
            RegexOptions.Singleline);
        Assert.True(enterCode.Success, "OnEnterCodeClick is gone — this test needs updating.");
        Assert.Matches(@"_longCode\s*=\s*false", enterCode.Value);
    }
}
