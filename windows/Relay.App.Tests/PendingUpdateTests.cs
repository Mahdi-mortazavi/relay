using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// The note an update leaves for the next launch to find.
///
/// It lives in a temp directory and it names something the app will execute, so
/// half of what follows is about refusing a note that is not one this app wrote.
/// </summary>
public class PendingUpdateTests
{
    private static string Fresh()
    {
        var directory = Path.Combine(
            Path.GetTempPath(), "relay-pending-tests", Guid.NewGuid().ToString("n"));
        Directory.CreateDirectory(directory);
        return directory;
    }

    private static void Write(string directory, string json) =>
        File.WriteAllText(Path.Combine(directory, PendingUpdate.FileName), json);

    [Fact]
    public void RoundTrips()
    {
        var directory = Fresh();
        var saved = new PendingUpdate("2.8.1", "Relay-Setup-x64.exe", new string('a', 64));

        PendingUpdate.Save(directory, saved);

        // Two sides written independently -- a writer and a hand-rolled reader --
        // so a renamed field would pass every other test in this file by making
        // Load return null, which reads as "nothing pending" and is exactly the
        // bug this class exists to fix.
        Assert.Equal(saved, PendingUpdate.Load(directory));
    }

    [Fact]
    public void NothingPendingWhenNothingWasSaved()
    {
        Assert.Null(PendingUpdate.Load(Fresh()));
    }

    [Fact]
    public void ClearMeansNothingPending()
    {
        var directory = Fresh();
        PendingUpdate.Save(directory, new PendingUpdate("2.8.1", "s.exe", "ab"));

        PendingUpdate.Clear(directory);

        // Cleared before the installer runs, so a launch that fails to start it
        // does not sit in a loop retrying the same bytes on every launch after.
        Assert.Null(PendingUpdate.Load(directory));
    }

    [Fact]
    public void RefusesAnInstallerNameThatIsAPath()
    {
        var directory = Fresh();
        Write(directory, """
            {"version":"99.0.0","installer":"..\\..\\Windows\\System32\\cmd.exe","sha256":"ab"}
            """);

        // This file sits in a world-writable temp directory and names a program
        // Relay will start. Combining a name with separators in it against the
        // download directory is how that becomes "Relay runs whatever was put
        // in front of it".
        Assert.Null(PendingUpdate.Load(directory));
    }

    [Fact]
    public void RefusesANoteThatDoesNotSayEverything()
    {
        var directory = Fresh();
        Write(directory, """{"version":"99.0.0","installer":"Relay-Setup-x64.exe"}""");

        // No hash means nothing can be checked before running it, which is the
        // one thing that must never be skipped.
        Assert.Null(PendingUpdate.Load(directory));
    }

    [Fact]
    public void RefusesRubbish()
    {
        var directory = Fresh();
        Write(directory, "not json at all");

        // A truncated write, a disk that filled, a file someone edited. None of
        // them should throw on a path that runs while the app is starting up.
        Assert.Null(PendingUpdate.Load(directory));
    }
}
