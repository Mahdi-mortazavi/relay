using System.Globalization;

namespace Relay.App;

/// <summary>
/// Localized strings held entirely in code (English + Persian). This app is
/// unpackaged, where the MRT <c>ResourceLoader</c> can throw a stowed COM
/// exception at startup that escapes managed try/catch; keeping strings in
/// code removes that failure mode entirely. Selection follows the OS UI
/// language; anything missing falls back to English, then to the key itself.
/// </summary>
public static class Strings
{
    private static readonly bool IsPersian =
        CultureInfo.CurrentUICulture.TwoLetterISOLanguageName.Equals("fa", StringComparison.OrdinalIgnoreCase);

    public static string Get(string key)
    {
        if (IsPersian && Fa.TryGetValue(key, out var fa)) return fa;
        return En.TryGetValue(key, out var en) ? en : key;
    }

    private static readonly Dictionary<string, string> En = new()
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
        ["ErrHostUnreachable"] = "Can't reach your phone. Make sure this PC is on the same Wi-Fi as the phone (or the phone's hotspot), then try again.",
        ["ErrWrongNetwork"] = "This PC isn't on the same network as the phone. Join the same Wi-Fi (or the phone's hotspot), then try again.",
        ["ErrConnectionLost"] = "The phone became unreachable. Re-check the hotspot and connect again.",
        ["ErrFirewall"] = "The connection was blocked. Allow Relay through Windows Firewall (or your security software), then try again.",
        ["ErrProxyApply"] = "Windows refused the proxy change. Close other proxy/VPN managers and try again.",
        ["ErrRollback"] = "Relay couldn't fully restore your proxy settings. Disconnect again to retry.",
        ["ErrCameraDenied"] = "Relay can't use the camera. Allow camera access for desktop apps in Windows Settings > Privacy, or enter the code manually.",
    };

    private static readonly Dictionary<string, string> Fa = new()
    {
        ["AppName"] = "رله",
        ["Tagline"] = "اتصالت را به‌اشتراک بگذار. در یک لحظه.",
        ["StatusIdle"] = "متصل نیست",
        ["StatusConnecting"] = "در حال اتصال…",
        ["StatusConnected"] = "متصل شد",
        ["ScanQr"] = "اسکن کد QR",
        ["EnterCode"] = "واردکردن دستی کد",
        ["ScanHint"] = "دوربین را به‌سمت کد QR روی گوشی بگیرید",
        ["CodeHint"] = "کد ۸ حرفی زیر کد QR در گوشی شما",
        ["Connect"] = "اتصال",
        ["Cancel"] = "انصراف",
        ["Disconnect"] = "قطع اتصال",
        ["Dismiss"] = "بستن",
        ["Reconnecting"] = "در حال اتصال مجدد…",
        ["TrayOpen"] = "بازکردن رله",
        ["TrayExit"] = "خروج",
        ["ConnectedVia"] = "متصل از طریق {0}",
        ["Advanced"] = "پیشرفته",
        ["AdvancedAddress"] = "نشانی هات‌اسپات",
        ["AdvancedLogs"] = "گزارش فعالیت (روی همین رایانه می‌ماند)",
        ["AdvancedLogsClear"] = "پاک‌کردن",
        ["AdvancedLogsEmpty"] = "هنوز فعالیتی نیست",
        ["ErrQrInvalid"] = "این کدِ رله نیست. کد QR را از برنامه رله روی گوشی نمایش دهید و دوباره تلاش کنید.",
        ["ErrQrNewer"] = "این کد با نسخه جدیدتری از رله ساخته شده — لطفاً این برنامه را به‌روزرسانی کنید.",
        ["ErrCodeInvalid"] = "این کد درست به‌نظر نمی‌رسد. ۸ حرف روی گوشی را بررسی کنید و دوباره تلاش کنید.",
        ["ErrHostUnreachable"] = "گوشی در دسترس نیست. مطمئن شوید این رایانه روی همان وای‌فای گوشی (یا هات‌اسپات آن) است و دوباره تلاش کنید.",
        ["ErrWrongNetwork"] = "این رایانه روی شبکه‌ی گوشی نیست. به همان وای‌فای (یا هات‌اسپات گوشی) وصل شوید و دوباره تلاش کنید.",
        ["ErrConnectionLost"] = "گوشی از دسترس خارج شد. هات‌اسپات را بررسی کنید و دوباره وصل شوید.",
        ["ErrFirewall"] = "اتصال مسدود شد. به رله در فایروال ویندوز (یا نرم‌افزار امنیتی) اجازه دهید و دوباره تلاش کنید.",
        ["ErrProxyApply"] = "ویندوز تغییر پراکسی را نپذیرفت. مدیریت‌کننده‌های دیگر پراکسی/VPN را ببندید و دوباره تلاش کنید.",
        ["ErrRollback"] = "رله نتوانست تنظیمات پراکسی را کاملاً بازگرداند. برای تلاش دوباره، دوباره «قطع اتصال» را بزنید.",
        ["ErrCameraDenied"] = "رله به دوربین دسترسی ندارد. در تنظیمات ویندوز > حریم خصوصی، دسترسی دوربین برنامه‌های دسکتاپ را فعال کنید، یا کد را دستی وارد کنید.",
    };
}
