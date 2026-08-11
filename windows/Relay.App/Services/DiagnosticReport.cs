using System.Reflection;
using System.Text;

namespace Relay.App.Services;

/// <summary>
/// Turns "it doesn't work" into something a developer can act on.
///
/// Built as text the person can read before they send it. Nothing is uploaded
/// from here and no service token is embedded: a token shipped inside an app is
/// a token anyone can extract, and a silent upload would break the promise the
/// log has always made — that it stays on this machine unless its owner decides
/// otherwise. Where it goes is the person's choice: the clipboard, or a GitHub
/// issue with the body already filled in.
/// </summary>
public static class DiagnosticReport
{
    private const int BodyLimit = 6000;
    private const string IssuesUrl = "https://github.com/Mahdi-mortazavi/relay/issues";

    /// <summary>Assembles the report from what this machine knows.</summary>
    public static string Build(string stateSummary, IReadOnlyList<LocalLog.Entry> entries)
    {
        var report = new StringBuilder();
        report.AppendLine("Relay diagnostic report");
        report.AppendLine("=======================");
        report.AppendLine();
        report.AppendLine($"App:      {Version()}");
        report.AppendLine($"Windows:  {Environment.OSVersion.VersionString}");
        report.AppendLine($"Arch:     {System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture}");
        report.AppendLine($"64-bit:   {Environment.Is64BitOperatingSystem}");
        report.AppendLine($"State:    {stateSummary}");
        report.AppendLine();
        report.AppendLine("Log (most recent last, times in seconds since launch)");
        report.AppendLine("----------------------------------------------------");
        if (entries.Count == 0)
        {
            report.AppendLine("(empty)");
        }
        else
        {
            foreach (var entry in entries)
            {
                report.AppendLine($"{entry.ElapsedSeconds,8:F2}  {entry.Message}");
            }
        }
        report.AppendLine();
        report.AppendLine("This report was assembled on this machine and shared by hand.");
        report.AppendLine("It contains no account data and nothing was uploaded automatically.");
        return report.ToString();
    }

    /// <summary>
    /// A GitHub issue with the report in the body. Truncated to fit a URL —
    /// browsers and servers both give up somewhere past 8k, and a link that
    /// silently fails to open is worse than a report missing its oldest lines.
    /// The newest lines explain the failure, so the front is what goes.
    /// </summary>
    public static string IssueUrl(string report)
    {
        var body = report.Length <= BodyLimit
            ? report
            : "(earlier lines trimmed to fit)\n" + report[^BodyLimit..];
        return $"{IssuesUrl}/new?title={Encode("Connection problem")}&body={Encode(body)}";
    }

    private static string Encode(string text) => Uri.EscapeDataString(text);

    private static string Version()
    {
        var version = Assembly.GetExecutingAssembly().GetName().Version;
        return version is null ? "unknown" : $"{version.Major}.{version.Minor}.{version.Build}";
    }
}
