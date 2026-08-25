using Microsoft.Win32;

namespace Relay.App.Services;

/// <summary>
/// Whether the tunnel closes the paths that go around it.
///
/// On by default, because the alternative is a leak. Windows resolves names on
/// every interface at once, so on a Wi-Fi the laptop shares with the phone the
/// router's resolver answers alongside the tunnel's — a leak test then lists
/// the local ISP next to the tunnel's exit. And this client configures IPv4
/// only, so on a network with working IPv6 every v6 connection left by the
/// physical adapter carrying the real address.
///
/// It is a setting rather than a fact because it has a cost. The filters permit
/// only the tunnel process, so Relay's own discovery — broadcast on the
/// physical adapter — is blocked while connected, and with it the ability to
/// follow a phone that changes address. Someone on a network where that matters
/// more than the leak should be able to say so, and be told what they are
/// trading.
///
/// Stored per user, like <see cref="StartupRegistration"/>, and read rather
/// than remembered so the value cannot drift from what the tunnel was actually
/// started with.
///
/// Loopback is exempt. The IPv6 rule is "all of ALE_AUTH_CONNECT_V6", and WFP
/// classifies loopback at that layer too, so it took ::1 with it — and Windows
/// resolves "localhost" to ::1 first, so while connected, every localhost
/// connection on the machine failed or stalled. That was unrelated software
/// breaking and being blamed on the tunnel. Permitting loopback cannot leak:
/// it never reaches a network interface.
///
/// This was briefly a switch that recorded an intent nothing acted on:
/// wireguard-windows' firewall package needs a service SID, which an elevated
/// user process does not have, so it refused before installing anything. The
/// tunnel client now writes its own WFP filters and the switch means what it
/// says. Verified on the topology the leak was reported from — the router's
/// resolver stops answering while connected, and every filter is gone the
/// instant the process is killed.
/// </summary>
public static class LeakProtection
{
    private const string Key = @"Software\Relay";
    private const string ValueName = "BlockLeaks";

    /// <summary>The flag the tunnel client takes. Must match its flag name.</summary>
    public const string DisableArgument = "-block-leaks=false";

    /// <summary>
    /// True unless the person has turned it off. Absent means on: a fresh
    /// install must not leak while waiting to be configured.
    /// </summary>
    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(Key);
            return key?.GetValue(ValueName) is not int value || value != 0;
        }
        catch (Exception)
        {
            // Unreadable registry is not a reason to start leaking.
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
