using System.Globalization;

namespace Relay.App;

/// <summary>
/// Localized strings held entirely in code (English + Persian). This app is
/// unpackaged, where the MRT <c>ResourceLoader</c> can throw a stowed COM
/// exception at startup that escapes managed try/catch; keeping strings in
/// code removes that failure mode entirely. Selection follows the OS UI
/// language; anything missing falls back to English, then to the key itself.
///
/// This is the only string store, and it has to stay that way. A pair of .resw
/// files sat alongside it for several releases, read by nothing: new keys were
/// added there, this file never got them, and the fallback quietly showed the
/// user identifiers — "CodeNoDevice" where a sentence should have been. If a
/// string is not here, it does not exist. <c>StringsCoverageTests</c> holds
/// that line.
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
        ["EnterCode"] = "Enter the 2-digit code",
        ["ScanHint"] = "Point the camera at the QR on your phone",
        ["CodeHint"] = "The two digits shown on your phone",
        ["CodeHintLong"] = "The 8-character code your phone is showing",
        ["Connect"] = "Connect",
        ["Cancel"] = "Cancel",
        ["Disconnect"] = "Disconnect",
        ["Dismiss"] = "Dismiss",
        ["Reconnecting"] = "Reconnecting…",
        ["TrayOpen"] = "Open Relay",
        ["TrayExit"] = "Exit",
        ["ConnectedVia"] = "Connected via {0}",
        ["Advanced"] = "Advanced",
        ["AdvancedVersion"] = "Version",
        ["AdvancedAddress"] = "Hotspot address",
        ["AdvancedLogs"] = "Activity log (stays on this PC)",
        ["AdvancedLogsClear"] = "Clear",
        // Copies the diagnostic report and opens a pre-filled issue. Named for
        // both halves, because the clipboard step is the surprising one.
        ["AdvancedLogsShare"] = "Copy & report",
        ["AdvancedLogsEmpty"] = "No activity yet",
        ["IdleHeadline"] = "Ready to connect",
        ["IdleBody"] = "Open Relay on your phone and tap Start Sharing. Then scan its QR, or type the two digits it shows.",
        ["ScanAiming"] = "Hold the QR inside the frame",
        ["CodeReady"] = "Looks good — connecting…",
        ["CodeHintShort"] = "One more digit",
        ["CodeDigitsOnly"] = "Two digits — the big number on the phone's screen",
        ["CodeNoLeadingZero"] = "Codes never start with a zero — check the first digit",
        ["CodeLooking"] = "Looking for that phone…",
        ["CodeFoundNamed"] = "Found {0} — connecting…",
        ["CodeAmbiguous"] = "Two phones are showing that code ({0}). Stop sharing on the one you don't want.",
        ["CodeNearby"] = "Sharing right now",
        ["CodeUseLong"] = "My phone shows a longer code",
        ["CodeUseShort"] = "My phone shows two digits",
        ["CodeIncomplete"] = "{0} more characters",
        ["CodeBadChars"] = "Only the letters and digits shown on the phone",
        ["CodeChecksum"] = "That code isn't valid. Check it against the phone.",
        ["BusyConnecting"] = "Setting up the connection",
        ["BusyDetail"] = "Checking the network and applying your proxy settings",
        ["ErrTitleNoPhone"] = "Can't reach your phone",
        ["ErrTitleNetwork"] = "Different networks",
        ["ErrTitleCode"] = "That code didn't work",
        ["ErrTitleCamera"] = "No camera available",
        ["ErrTitleBlocked"] = "Connection blocked",
        ["ErrTitleProxy"] = "Windows refused the change",
        ["ErrTitleRollback"] = "Proxy not fully restored",
        ["ErrTitleLost"] = "Connection lost",
        ["TryAgain"] = "Try Again",
        ["EnterCodeInstead"] = "Enter Code Instead",
        ["ErrQrInvalid"] = "That's not a Relay code. Show the QR from the Relay app on your phone and try again.",
        ["ErrQrNewer"] = "This code was made by a newer version of Relay — please update this app.",
        ["ErrCodeInvalid"] = "That code doesn't look right. Type the two digits shown on the phone.",
        ["ErrCodeNotFound"] = "No phone on this network is showing that code. Check that Relay is still sharing on the phone, and that this PC is on the phone's hotspot or the same Wi-Fi.",
        ["ErrCodeAmbiguous"] = "More than one phone is showing that code. Stop sharing on the one you don't want, then try again.",
        ["ErrFullModeNeedsQr"] = "That phone is sharing in Full Mode, which can only be paired by scanning its QR code.",
        ["ErrHostUnreachable"] = "Can't reach your phone. Make sure this PC is on the same Wi-Fi as the phone (or the phone's hotspot), then try again.",
        ["ErrWrongNetwork"] = "This PC isn't on the same network as the phone. Join the same Wi-Fi (or the phone's hotspot), then try again.",
        ["ErrConnectionLost"] = "The phone became unreachable. Re-check the hotspot and connect again.",
        ["ErrFirewall"] = "The connection was blocked. Allow Relay through Windows Firewall (or your security software), then try again.",
        ["ErrProxyApply"] = "Windows refused the proxy change. Close other proxy/VPN managers and try again.",
        ["ErrRollback"] = "Relay couldn't fully restore your proxy settings. Disconnect again to retry.",
        ["ErrTitleElevation"] = "Full Mode needs permission",
        ["ErrTitleTunnel"] = "The tunnel didn't start",
        ["ErrWgElevationDeclined"] = "Full Mode has to create a network adapter, which Windows only allows with your permission. Choose Yes on the prompt, or switch the phone to Fast Mode.",
        ["ErrWgStartFailed"] = "Relay couldn't bring the tunnel up. Close any other VPN that is running, then try again — or switch the phone to Fast Mode.",
        ["ErrWgStopFailed"] = "The tunnel process wouldn't stop. Restart Relay; the adapter and its routes are removed when it exits.",
        ["ErrCameraDenied"] = "Relay can't use the camera — it may be missing, in use by another app, or blocked. Allow camera access in Windows Settings > Privacy, or enter the code manually.",
    };

    private static readonly Dictionary<string, string> Fa = new()
    {
        ["AppName"] = "رله",
        ["Tagline"] = "اتصالت را به‌اشتراک بگذار. در یک لحظه.",
        ["StatusIdle"] = "متصل نیست",
        ["StatusConnecting"] = "در حال اتصال…",
        ["StatusConnected"] = "متصل شد",
        ["ScanQr"] = "اسکن کد QR",
        ["EnterCode"] = "واردکردن کد ۲ رقمی",
        ["ScanHint"] = "دوربین را به‌سمت کد QR روی گوشی بگیرید",
        ["CodeHint"] = "همان دو رقمی که روی گوشی نوشته شده",
        ["CodeHintLong"] = "کد ۸ حرفی که گوشی نشان می‌دهد",
        ["Connect"] = "اتصال",
        ["Cancel"] = "انصراف",
        ["Disconnect"] = "قطع اتصال",
        ["Dismiss"] = "بستن",
        ["Reconnecting"] = "در حال اتصال مجدد…",
        ["TrayOpen"] = "بازکردن رله",
        ["TrayExit"] = "خروج",
        ["ConnectedVia"] = "متصل از طریق {0}",
        ["Advanced"] = "پیشرفته",
        ["AdvancedVersion"] = "نسخه",
        ["AdvancedAddress"] = "نشانی هات‌اسپات",
        ["AdvancedLogs"] = "گزارش فعالیت (روی همین رایانه می‌ماند)",
        ["AdvancedLogsClear"] = "پاک‌کردن",
        ["AdvancedLogsShare"] = "کپی و گزارش",
        ["AdvancedLogsEmpty"] = "هنوز فعالیتی نیست",
        ["IdleHeadline"] = "آمادهٔ اتصال",
        ["IdleBody"] = "رله را روی گوشی باز کنید و «شروع اشتراک‌گذاری» را بزنید. بعد کد QR آن را اسکن کنید، یا همان دو رقمی را که نشان می‌دهد تایپ کنید.",
        ["ScanAiming"] = "کد QR را داخل کادر نگه دارید",
        ["CodeReady"] = "درست است — در حال اتصال…",
        ["CodeHintShort"] = "یک رقم دیگر",
        ["CodeDigitsOnly"] = "دو رقم — همان عدد درشت روی صفحهٔ گوشی",
        ["CodeNoLeadingZero"] = "کدها هیچ‌وقت با صفر شروع نمی‌شوند — رقم اول را دوباره ببینید",
        ["CodeLooking"] = "در حال گشتن به‌دنبال آن گوشی…",
        ["CodeFoundNamed"] = "{0} پیدا شد — در حال اتصال…",
        ["CodeAmbiguous"] = "دو گوشی همین کد را نشان می‌دهند ({0}). روی آن‌که نمی‌خواهید اشتراک‌گذاری را متوقف کنید.",
        ["CodeNearby"] = "همین حالا در حال اشتراک‌گذاری",
        ["CodeUseLong"] = "گوشی من کد بلندتری نشان می‌دهد",
        ["CodeUseShort"] = "گوشی من دو رقم نشان می‌دهد",
        ["CodeIncomplete"] = "{0} حرف دیگر",
        ["CodeBadChars"] = "فقط حروف و ارقامی که روی گوشی نشان داده شده",
        ["CodeChecksum"] = "این کد معتبر نیست. آن را با گوشی بررسی کنید.",
        ["BusyConnecting"] = "در حال برقراری اتصال",
        ["BusyDetail"] = "بررسی شبکه و اعمال تنظیمات پراکسی",
        ["ErrTitleNoPhone"] = "گوشی در دسترس نیست",
        ["ErrTitleNetwork"] = "شبکه‌ها یکی نیستند",
        ["ErrTitleCode"] = "این کد کار نکرد",
        ["ErrTitleCamera"] = "دوربینی در دسترس نیست",
        ["ErrTitleBlocked"] = "اتصال مسدود شد",
        ["ErrTitleProxy"] = "ویندوز تغییر را نپذیرفت",
        ["ErrTitleRollback"] = "پراکسی کاملاً بازنگشت",
        ["ErrTitleLost"] = "اتصال قطع شد",
        ["TryAgain"] = "تلاش دوباره",
        ["EnterCodeInstead"] = "به‌جایش کد را وارد کنید",
        ["ErrQrInvalid"] = "این کدِ رله نیست. کد QR را از برنامه رله روی گوشی نمایش دهید و دوباره تلاش کنید.",
        ["ErrQrNewer"] = "این کد با نسخه جدیدتری از رله ساخته شده — لطفاً این برنامه را به‌روزرسانی کنید.",
        ["ErrCodeInvalid"] = "این کد درست به‌نظر نمی‌رسد. همان دو رقمی را که روی گوشی نوشته شده وارد کنید.",
        ["ErrCodeNotFound"] = "هیچ گوشی‌ای روی این شبکه این کد را نشان نمی‌دهد. مطمئن شوید رله روی گوشی هنوز در حال اشتراک‌گذاری است و این رایانه به هات‌اسپات گوشی یا همان وای‌فای وصل است.",
        ["ErrCodeAmbiguous"] = "بیش از یک گوشی این کد را نشان می‌دهد. روی آن‌که نمی‌خواهید اشتراک‌گذاری را متوقف کنید و دوباره تلاش کنید.",
        ["ErrFullModeNeedsQr"] = "آن گوشی در حالت کامل اشتراک می‌گذارد؛ این حالت فقط با اسکن کد QR جفت می‌شود.",
        ["ErrHostUnreachable"] = "گوشی در دسترس نیست. مطمئن شوید این رایانه روی همان وای‌فای گوشی (یا هات‌اسپات آن) است و دوباره تلاش کنید.",
        ["ErrWrongNetwork"] = "این رایانه روی شبکه‌ی گوشی نیست. به همان وای‌فای (یا هات‌اسپات گوشی) وصل شوید و دوباره تلاش کنید.",
        ["ErrConnectionLost"] = "گوشی از دسترس خارج شد. هات‌اسپات را بررسی کنید و دوباره وصل شوید.",
        ["ErrFirewall"] = "اتصال مسدود شد. به رله در فایروال ویندوز (یا نرم‌افزار امنیتی) اجازه دهید و دوباره تلاش کنید.",
        ["ErrProxyApply"] = "ویندوز تغییر پراکسی را نپذیرفت. مدیریت‌کننده‌های دیگر پراکسی/VPN را ببندید و دوباره تلاش کنید.",
        ["ErrRollback"] = "رله نتوانست تنظیمات پراکسی را کاملاً بازگرداند. برای تلاش دوباره، دوباره «قطع اتصال» را بزنید.",
        ["ErrTitleElevation"] = "حالت کامل به اجازه نیاز دارد",
        ["ErrTitleTunnel"] = "تونل بالا نیامد",
        ["ErrWgElevationDeclined"] = "حالت کامل باید یک آداپتور شبکه بسازد و ویندوز این کار را فقط با اجازهٔ شما انجام می‌دهد. در پنجرهٔ ویندوز «بله» را بزنید، یا گوشی را روی حالت سریع بگذارید.",
        ["ErrWgStartFailed"] = "رله نتوانست تونل را بالا بیاورد. اگر VPN دیگری روشن است ببندید و دوباره تلاش کنید — یا گوشی را روی حالت سریع بگذارید.",
        ["ErrWgStopFailed"] = "پروسهٔ تونل بسته نشد. رله را دوباره اجرا کنید؛ آداپتور و مسیرهایش با بسته شدن آن پاک می‌شوند.",
        ["ErrCameraDenied"] = "رله به دوربین دسترسی ندارد — ممکن است نبودن دوربین، اشغال توسط برنامه‌ای دیگر، یا مسدودبودن باشد. در تنظیمات ویندوز > حریم خصوصی دسترسی دوربین را فعال کنید، یا کد را دستی وارد کنید.",
    };
}
