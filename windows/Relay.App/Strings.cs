using Microsoft.Windows.ApplicationModel.Resources;

namespace Relay.App;

/// <summary>
/// Localized strings with a hard English fallback: a resource-loading failure
/// must never crash the tray app or leave blank UI.
/// </summary>
public static class Strings
{
    private static readonly ResourceLoader? Loader = Create();

    private static ResourceLoader? Create()
    {
        try
        {
            return new ResourceLoader();
        }
        catch (Exception)
        {
            return null;
        }
    }

    public static string Get(string key)
    {
        try
        {
            var value = Loader?.GetString(key);
            if (!string.IsNullOrEmpty(value)) return value;
        }
        catch (Exception)
        {
            // fall through to the fallback table
        }
        return Fallback.TryGetValue(key, out var english) ? english : key;
    }

    private static readonly Dictionary<string, string> Fallback = new()
    {
        ["AppName"] = "Relay",
        ["Tagline"] = "Share your connection. Instantly.",
        ["StatusIdle"] = "Not connected",
        ["StatusConnecting"] = "Connecting…",
        ["StatusConnected"] = "Connected",
        ["ScanQr"] = "Scan QR",
        ["EnterCode"] = "Enter Code Manually",
        ["ScanHint"] = "Point the camera at the QR on your phone",
        ["CodeHint"] = "The 8-character code shown under the QR on your phone",
        ["Connect"] = "Connect",
        ["Cancel"] = "Cancel",
        ["Disconnect"] = "Disconnect",
        ["Dismiss"] = "Dismiss",
        ["Reconnecting"] = "Reconnecting…",
        ["TrayOpen"] = "Open Relay",
        ["TrayExit"] = "Exit",
        ["ConnectedVia"] = "Connected via {0}",
        ["Advanced"] = "Advanced",
        ["AdvancedAddress"] = "Hotspot address",
        ["AdvancedLogs"] = "Activity log (stays on this PC)",
        ["AdvancedLogsClear"] = "Clear",
        ["AdvancedLogsEmpty"] = "No activity yet",
        ["ErrQrInvalid"] = "That's not a Relay code. Show the QR from the Relay app on your phone and try again.",
        ["ErrQrNewer"] = "This code was made by a newer version of Relay — please update this app.",
        ["ErrCodeInvalid"] = "That code doesn't look right. Check the 8 characters on the phone and try again.",
        ["ErrHostUnreachable"] = "Can't reach your phone. Connect this PC to the phone's hotspot Wi-Fi, then try again.",
        ["ErrWrongNetwork"] = "This PC isn't on the phone's hotspot. Join the phone's Wi-Fi, then try again.",
        ["ErrConnectionLost"] = "The phone became unreachable. Re-check the hotspot and connect again.",
        ["ErrFirewall"] = "The connection was blocked. Allow Relay through Windows Firewall (or your security software), then try again.",
        ["ErrProxyApply"] = "Windows refused the proxy change. Close other proxy/VPN managers and try again.",
        ["ErrRollback"] = "Relay couldn't fully restore your proxy settings. Disconnect again to retry.",
        ["ErrCameraDenied"] = "Relay can't use the camera. Allow camera access for desktop apps in Windows Settings > Privacy, or enter the code manually.",
    };
}
