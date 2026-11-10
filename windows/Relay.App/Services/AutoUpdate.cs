using Microsoft.Win32;

namespace Relay.App.Services;

/// <summary>
/// Whether Relay checks for, downloads and installs new versions by itself.
///
/// On by default, because a tray app nobody updates stays on its first
/// version. It is a setting because installing replaces the app and closes it,
/// and someone should be able to say "not without me" (#173). Off means no
/// check is made at all: nothing is fetched and nothing is installed. The
/// setting is read once at launch, so a change applies from the next start.
/// </summary>
public static class AutoUpdate
{
    private const string Key = @"Software\Relay";
    private const string ValueName = "AutoUpdate";

    /// <summary>True unless the person turned it off. Absent means on.</summary>
    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(Key);
            return key?.GetValue(ValueName) is not int value || value != 0;
        }
        catch (Exception)
        {
            return true;
        }
    }

    /// <summary>Returns whether the registry now says what was asked.</summary>
    public static bool Set(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(Key, writable: true);
            if (key is null) return false;
            key.SetValue(ValueName, enabled ? 1 : 0, RegistryValueKind.DWord);
            return IsEnabled() == enabled;
        }
        catch (Exception)
        {
            return false;
        }
    }
}
