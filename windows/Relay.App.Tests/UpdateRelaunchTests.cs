using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// That the two halves of the relaunch still agree.
///
/// An automatic update closes Relay so Setup's AppMutex check does not stop on
/// a dialog, which means something has to start it again. The flag the app
/// passes and the parameter the installer script reads are written in two
/// different languages in two different files, and nothing but this connects
/// them: rename one and the update would go on working right up to the point
/// where the app disappears and never comes back — a failure no unit test would
/// see and no CI job would fail on, because both halves are individually fine.
/// </summary>
public class UpdateRelaunchTests
{
    private static string Script => File.ReadAllText(Path.Combine(
        SharedContracts.RepoRoot, "windows", "installer", "relay.iss"));

    [Fact]
    public void TheInstallerReadsTheParameterTheAppSends()
    {
        // "/relaunch=1" -> the name Inno resolves in {param:relaunch|...}.
        var name = UpdateInstaller.Relaunch.TrimStart('/').Split('=')[0];

        Assert.Contains("{param:" + name + "|", Script);
    }

    [Fact]
    public void SomethingActuallyStartsRelayWhenThatParameterIsSet()
    {
        // The check exists and a [Run] line is gated on it. Without the second
        // line the parameter would parse and do nothing.
        Assert.Contains("function RelaunchRequested", Script);
        Assert.Contains("Check: RelaunchRequested", Script);
    }
}
