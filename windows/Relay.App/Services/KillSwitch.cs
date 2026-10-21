using Microsoft.Win32;

namespace Relay.App.Services;

/// <summary>
/// Whether the tunnel keeps blocking when the phone stops answering (#165).
///
/// Without it, a phone that goes quiet — a lag, a screen-off kill, a hotspot
/// that dropped — ends the tunnel after three minutes, its default route goes
/// with it, and every app on the laptop quietly carries on over the laptop's
/// own connection. Most people want exactly that: the internet keeps working.
/// Someone who uses Relay so that traffic goes <em>only</em> through the phone
/// does not, and was told nothing.
///
/// With it on, the tunnel stays up with nobody answering, so the machine's
/// traffic goes into it and stops there. The window says it is reconnecting,
/// and the moment the phone answers again — same sharing session, same keys —
/// traffic flows with nothing pressed. Disconnect is the way out, and it always
/// works, because that closes the pipe the tunnel lives on.
///
/// Off by default, because on, a phone that has gone for good blocks the
/// laptop's internet until someone presses Disconnect, and that must be a
/// choice. Stored like <see cref="LeakProtection"/>, and read at connect time.
/// </summary>
public static class KillSwitch
{
    private const string Key = @"Software\Relay";
    private const string ValueName = "KillSwitch";

    /// <summary>False unless the person turned it on. Absent or unreadable means off.</summary>
    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(Key);
            return key?.GetValue(ValueName) is int value && value != 0;
        }
        catch (Exception)
        {
            return false;
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
