using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

public class LeakExclusionsTests
{
    [Fact]
    public void DefaultsAreFailClosed()
    {
        var d = LeakExclusions.Default;
        Assert.False(d.AllowLan);
        Assert.False(d.AllowDiscovery);
        Assert.True(d.StrictLeakBlock);
        Assert.False(d.PersistKillSwitch);
        Assert.Empty(d.ExcludeApps);
        Assert.Empty(d.ExcludeCidrs);
    }

    [Fact]
    public void MissingOrMalformedFileFallsBackToStrictDefaults()
    {
        var temp = Path.Combine(Path.GetTempPath(), $"relay-excl-{Guid.NewGuid():N}.json");
        try
        {
            Assert.Equal(LeakExclusions.Default, LeakExclusions.LoadOrDefault(temp));

            File.WriteAllText(temp, "{ not valid json");
            var warnings = new List<string>();
            var loaded = LeakExclusions.LoadOrDefault(temp, warnings.Add);

            Assert.True(loaded.StrictLeakBlock);
            Assert.False(loaded.AllowLan);
            Assert.NotEmpty(warnings);
        }
        finally
        {
            try { File.Delete(temp); } catch { }
        }
    }

    [Fact]
    public void OmittingStrictLeakBlockInJsonStillKeepsStrictTrue()
    {
        var temp = Path.Combine(Path.GetTempPath(), $"relay-excl-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(temp, """{ "allowLan": true }""");
            var loaded = LeakExclusions.LoadOrDefault(temp);

            Assert.True(loaded.AllowLan);
            Assert.True(loaded.StrictLeakBlock);
        }
        finally
        {
            try { File.Delete(temp); } catch { }
        }
    }

    [Fact]
    public void SanitizeDropsUnsafeAndMalformedEntriesAndNormalizesCidrs()
    {
        var dirty = new LeakExclusions
        {
            AllowLan = true,
            AllowDiscovery = true,
            PersistKillSwitch = true,
            ExcludeApps =
            [
                @"C:\Program Files\Tailscale\tailscale.exe",
                @"C:\Program Files\Tailscale\tailscale.exe", // duplicate
                "tailscale.exe",                             // relative
                @"C:\Windows\System32\svchost.exe",          // dangerous system host
                @"C:\Windows\System32\cmd.exe",              // dangerous shell
                @"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe",
                @"C:\Temp\bad,comma.exe",                    // delimiter
                @"C:\Temp\library.dll",                      // non-exe
            ],
            ExcludeCidrs =
            [
                "192.168.10.42/24",                          // masked to network
                "0.0.0.0/0",                                 // default route rejected
                "::/0",                                      // default route rejected
                "192.168.1.1",                               // missing prefix
                "not-an-ip/24",                              // invalid ip
                "fd00::5/64",                                // masked to network
            ],
        };

        var warnings = new List<string>();
        var clean = dirty.Sanitize(warnings.Add);

        Assert.Single(clean.ExcludeApps);
        Assert.Equal(Path.GetFullPath(@"C:\Program Files\Tailscale\tailscale.exe"), clean.ExcludeApps[0]);

        Assert.Equal(new[] { "192.168.10.0/24", "fd00::/64" }, clean.ExcludeCidrs);
        Assert.True(warnings.Count >= 9);
    }

    [Fact]
    public void SaveRoundTripsSanitizedExclusionsAndReportsWarnings()
    {
        var temp = Path.Combine(Path.GetTempPath(), $"relay-excl-save-{Guid.NewGuid():N}.json");
        try
        {
            var input = new LeakExclusions
            {
                AllowLan = true,
                AllowDiscovery = true,
                PersistKillSwitch = true,
                ExcludeApps =
                [
                    @"C:\Program Files\RustDesk\rustdesk.exe",
                    @"C:\Windows\System32\cmd.exe",
                ],
                ExcludeCidrs = ["192.168.1.44/24", "0.0.0.0/0"],
            };

            var (ok, saved, warnings) = LeakExclusions.Save(input, temp);

            Assert.True(ok);
            Assert.Equal(2, warnings.Count);
            Assert.Single(saved.ExcludeApps);
            Assert.Single(saved.ExcludeCidrs);

            var reloaded = LeakExclusions.LoadOrDefault(temp);
            Assert.True(reloaded.AllowLan);
            Assert.True(reloaded.AllowDiscovery);
            Assert.True(reloaded.PersistKillSwitch);
            Assert.Equal(new[] { Path.GetFullPath(@"C:\Program Files\RustDesk\rustdesk.exe") }, reloaded.ExcludeApps);
            Assert.Equal(new[] { "192.168.1.0/24" }, reloaded.ExcludeCidrs);
        }
        finally
        {
            try { File.Delete(temp); } catch { }
            try { File.Delete(temp + ".tmp"); } catch { }
        }
    }

    [Fact]
    public void SplitEntriesHandlesNewlinesCommasAndQuotes()
    {
        var split = LeakExclusions.SplitEntries("\"C:\\Apps\\a.exe\",\n  \"C:\\Apps\\b.exe\"; 192.168.1.0/24\r\n");
        Assert.Equal(new[] { @"C:\Apps\a.exe", @"C:\Apps\b.exe", "192.168.1.0/24" }, split);
    }
}
