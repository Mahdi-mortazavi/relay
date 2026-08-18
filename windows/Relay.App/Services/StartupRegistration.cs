using Microsoft.Win32;

namespace Relay.App.Services;

/// <summary>
/// Whether Relay starts with Windows.
///
/// This is the per-user <c>Run</c> key, deliberately: it needs no elevation, it
/// is the one place a person can see and undo the entry themselves (Task
/// Manager → Startup apps lists it, and its switch overrides ours), and it
/// leaves nothing behind for another account. A scheduled task or the
/// machine-wide key would each buy a privilege prompt Relay does not otherwise
/// need (ADR-0005).
///
/// Relay starts hidden — see <c>--tray</c> in <c>App.xaml.cs</c> — because a
/// window that appears over whatever you were doing at login is a reason people
/// turn this off again.
///
/// Every method here swallows registry failures and reports the outcome instead
/// of throwing. A locked-down machine may deny the write, and a tray app that
/// crashes on launch because it could not tick a checkbox is a worse failure
/// than one that quietly is not registered.
/// </summary>
public static class StartupRegistration
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    /// <summary>The value name under Run. Stable — renaming it strands the old entry.</summary>
    private const string ValueName = "Relay";

    /// <summary>Told to the app at login so it comes up in the tray, not on screen.</summary>
    public const string TrayArgument = "--tray";

    /// <summary>Where this build actually lives, quoted for the spaces in Program Files.</summary>
    private static string CommandLine =>
        $"\"{Environment.ProcessPath ?? string.Empty}\" {TrayArgument}";

    /// <summary>
    /// True when Relay is registered <em>and</em> the registered path is this
    /// build. A stale entry pointing at an install that has since moved is
    /// worse than no entry: Windows reports a startup app that silently fails,
    /// so it counts as "off" here and is rewritten when switched on.
    /// </summary>
    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) as string == CommandLine;
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>Turns it on or off. Returns whether the registry now says what was asked.</summary>
    public static bool Set(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            if (key is null) return false;

            if (enabled)
            {
                if (string.IsNullOrEmpty(Environment.ProcessPath)) return false;
                key.SetValue(ValueName, CommandLine, RegistryValueKind.String);
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
            return IsEnabled() == enabled;
        }
        catch (Exception)
        {
            return false;
        }
    }
}
