using Microsoft.Win32;

namespace Relay.App.Services;

/// <summary>
/// Makes the next sign-in repair a session that never got to disconnect.
///
/// Relay's rollback runs on <c>ProcessExit</c>, which the CLR raises only on a
/// graceful shutdown. A Windows sign-out, a restart, a <c>taskkill /f</c>, or an
/// installer force-closing the app during an upgrade all terminate the process
/// without it — and the machine is then left with <c>ProxyEnable=1</c> pointing
/// at a phone that is no longer there. Every WinINet app on that machine loses
/// its network, the user has no reason to connect that to "the tray app I used
/// yesterday", and Relay's own <see cref="ProxySession.RecoverIfCrashed"/> only
/// helps if somebody happens to launch Relay again.
///
/// So for exactly as long as a proxy is applied, a per-user Run entry is
/// registered that launches Relay once with <c>--restore-proxy</c>. It is
/// removed the moment the session ends cleanly, so this is never a permanent
/// autostart — it exists only while the machine is actually at risk.
/// </summary>
internal static class ShutdownRecoveryHook
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "RelayProxyRecovery";

    /// <summary>Called once a proxy has been applied. Best-effort; never throws.</summary>
    public static void Arm()
    {
        try
        {
            var exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe)) return;
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            key.SetValue(ValueName, $"\"{exe}\" --restore-proxy", RegistryValueKind.String);
        }
        catch (Exception ex)
        {
            // A missing recovery hook degrades safety, but failing to write it
            // must never fail the connection the user asked for.
            LocalLog.Add($"Could not arm the shutdown recovery hook: {ex.Message}");
        }
    }

    /// <summary>Called once the proxy is back to the user's own settings.</summary>
    public static void Disarm()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
            key?.DeleteValue(ValueName, throwOnMissingValue: false);
        }
        catch (Exception)
        {
            // Worst case the hook fires once more and finds nothing to restore,
            // which RecoverIfCrashed handles as a no-op.
        }
    }
}
