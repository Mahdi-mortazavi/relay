using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

public class DiagnosticReportTests
{
    private static (double, string) Entry(double at, string message) => (at, message);

    [Fact]
    public void Includes_the_state_and_the_log()
    {
        var report = DiagnosticReport.Build("Error: ERR_PROXY_SET", [Entry(1.5, "Bound proxy"), Entry(2.0, "Failed")]);
        Assert.Contains("Error: ERR_PROXY_SET", report);
        Assert.Contains("Bound proxy", report);
        Assert.Contains("Failed", report);
    }

    [Fact]
    public void Says_so_when_there_is_nothing_to_report()
    {
        // An empty section reads as a truncated file; "(empty)" reads as a fact.
        var report = DiagnosticReport.Build("Idle", []);
        Assert.Contains("(empty)", report);
    }

    [Fact]
    public void States_that_nothing_was_uploaded()
    {
        // The log's promise is that it stays here. Someone about to paste this
        // into a public issue should be able to see that promise in the text.
        var report = DiagnosticReport.Build("Idle", []);
        Assert.Contains("nothing was uploaded automatically", report);
    }

    [Fact]
    public void Issue_url_carries_the_report()
    {
        var report = DiagnosticReport.Build("Idle", [Entry(0.1, "hello")]);
        var url = DiagnosticReport.IssueUrl(report);
        Assert.StartsWith("https://github.com/Mahdi-mortazavi/relay/issues/new?", url);
        Assert.Contains(Uri.EscapeDataString("hello"), url);
    }

    [Fact]
    public void Issue_url_keeps_the_newest_lines_when_it_has_to_trim()
    {
        // The end of a log explains a failure; the beginning is startup noise.
        // Trimming the wrong end would throw away the only useful part.
        var entries = Enumerable.Range(0, 2000)
            .Select(i => Entry(i, $"line-{i}-{new string('x', 20)}"))
            .ToList();
        var report = DiagnosticReport.Build("Error: ERR_SOMETHING", entries);
        var url = DiagnosticReport.IssueUrl(report);

        Assert.Contains(Uri.EscapeDataString("line-1999"), url);
        Assert.DoesNotContain(Uri.EscapeDataString("line-0-"), url);
        Assert.Contains(Uri.EscapeDataString("earlier lines trimmed"), url);
    }

    [Fact]
    public void Issue_url_stays_inside_what_a_browser_will_open()
    {
        // Past roughly 8k a URL stops working, and it fails by doing nothing,
        // which looks to the user like the button is broken.
        var entries = Enumerable.Range(0, 5000).Select(i => Entry(i, new string('y', 50))).ToList();
        var url = DiagnosticReport.IssueUrl(DiagnosticReport.Build("Idle", entries));
        Assert.True(url.Length < 8000, $"URL is {url.Length} characters — too long to open");
    }

    [Fact]
    public void Escapes_text_that_would_otherwise_break_the_url()
    {
        var report = DiagnosticReport.Build("Idle", [Entry(0, "host=192.168.1.1&port=1080 #1 100%")]);
        var url = DiagnosticReport.IssueUrl(report);
        // A raw & would truncate the body at that point and silently lose the
        // rest of the report; a raw % would make the URL malformed.
        var body = url[(url.IndexOf("&body=", StringComparison.Ordinal) + 6)..];
        Assert.DoesNotContain("&", body);
        Assert.Contains("%23", url); // #
        Assert.Contains("%25", url); // %
    }
}
