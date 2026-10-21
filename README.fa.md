<div align="center">

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/cover-mobile.svg">
  <img src="docs/assets/cover.svg" alt="رله — اینترنت گوشی‌ات را با کامپیوترت به اشتراک بگذار. Relay — share your phone's internet with your PC." width="100%">
</picture>

<br>

[<img src="docs/assets/btn-windows-fa.svg" alt="دانلود برای ویندوز" height="46">](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe)
&nbsp;
[<img src="docs/assets/btn-android-fa.svg" alt="دانلود برای اندروید" height="46">](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk)
&nbsp;
[<img src="docs/assets/btn-film-fa.svg" alt="تماشای ویدیو" height="46">](#ببین-چطور-کار-می‌کند)
&nbsp;
[<img src="docs/assets/btn-english.svg" alt="Read in English" height="46">](README.md)

[![CI](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml)
[![Device lab](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml)
[![Release](https://img.shields.io/github/v/release/Mahdi-mortazavi/relay?sort=semver&color=4ADFBF&label=release)](https://github.com/Mahdi-mortazavi/relay/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-4ADFBF)](LICENSE)

</div>

<div dir="rtl" align="center">

**اینترنت گوشی‌ات را با کامپیوترت به اشتراک بگذار — همهٔ برنامه‌ها، هم TCP هم UDP، از داخل یک تونل رمزنگاری‌شدهٔ WireGuard.**<br>
بدون روت · بدون حساب کاربری · بدون سرور · هیچ‌چیز از این دو دستگاه بیرون نمی‌رود

</div>

<br>

<div dir="rtl">

## در سه قدم

</div>

<div align="center">

| ۳ · گوشی | ۲ · کامپیوتر | ۱ · گوشی |
|:---:|:---:|:---:|
| **اجازه بده** را بزن | گوشی‌ات را از فهرست انتخاب کن<br><sub>یا دو رقمی را که نشان می‌دهد تایپ کن</sub> | **شروع اشتراک‌گذاری** را بزن |

</div>

<div dir="rtl">

از هر چیزی که همین حالا دو دستگاه را به هم وصل می‌کند استفاده کن: **وای‌فای خودت**،
**هات‌اسپات گوشی** یا **کابل USB**. اگر کابل وصل باشد، رله پیشنهاد می‌دهد اشتراک
اینترنت با USB را روشن کنی. لپ‌تاپ دوربین ندارد؟ دو رقم کافی است. گوشی در فهرست
نیامد؟ کد QR را اسکن کن.

</div>

<div align="center">

<img src="docs/assets/android-sharing.png" alt="رله روی اندروید در حال اشتراک‌گذاری: کد دو رقمی و کد QR" width="260">
&nbsp;&nbsp;&nbsp;
<img src="docs/assets/windows-idle.png" alt="رله روی ویندوز: پنجره‌ای جمع‌وجور در سینی سیستم" width="300">

</div>

<div dir="rtl">

## ببین چطور کار می‌کند

شصت ثانیه، بدون روایت: دو رقم، آنلاین شدن کامپیوتر، و دانلودی که ادامه می‌دهد در
حالی که گوشی زیرش از وای‌فای به LTE می‌رود.

</div>

<div align="center">

<img src="docs/assets/relay-film.gif" alt="جفت‌شدن رله و آنلاین شدن کامپیوتر: گوشی کد دو رقمی را نشان می‌دهد، کامپیوتر آنلاین می‌شود، و دانلود حتی وقتی گوشی از وای‌فای به LTE می‌رود ادامه دارد" width="760">

</div>

https://github.com/user-attachments/assets/fcfbc7ec-e61f-48c0-96b8-cd4713abb49b

<div dir="rtl">

<sub>انیمیشن بالا طراحی‌شده است و ویدیو صدا دارد. نسخهٔ واقعی و بدون تدوین: **[راهنمای چهاردقیقه‌ای کسری مکس](https://raw.githubusercontent.com/Mahdi-mortazavi/relay/main/docs/assets/relay-demo-kasra.mp4)** از نصب تا وب‌گردی، با روایت فارسی و زیرنویس انگلیسی. ممنون کسرا، هم برای این ویدیو و هم برای باگ‌هایی که پیدا کردی.</sub>

## دانلود

| | فایل | برای |
|---|---|---|
| **ویندوز** | [**Relay-Setup-x64.exe**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe) | ویندوز ۱۰ و ۱۱ · فقط برای کاربر خودت نصب می‌شود، بدون نیاز به ادمین |
| **اندروید** | [**Relay-android-arm64-v8a.apk**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk) | اندروید ۸ به بالا · تقریباً همهٔ گوشی‌های بعد از ۲۰۱۷ |
| اندروید، همهٔ پردازنده‌ها | [Relay-android-universal.apk](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-universal.apk) | اگر فایل بالا گفت «برنامه نصب نشد» |
| ویندوز ۳۲ بیتی | [Relay-Setup-x86.exe](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x86.exe) | فقط اگر مطمئنی لازمش داری |

- **ویندوز بار اول هشدار می‌دهد** چون نصب‌کننده هنوز امضای دیجیتال ندارد: **More info ← Run anyway**. هر نسخه فایل [`SHA256SUMS.txt`](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/SHA256SUMS.txt) دارد تا فایل را بررسی کنی.
- **اندروید گفت «برنامه نصب نشد»؟** [همهٔ دلیل‌هایی که تا حالا دیده شده](docs/install-troubleshooting.md).
- **به‌روزرسانی:** ویندوز وقتی وصل نیستی خودش آپدیت می‌شود. اندروید هر نسخهٔ جدید را پیشنهاد می‌دهد (اندروید نصب بی‌صدا را اجازه نمی‌دهد)، یا با [**Obtainium**](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Mahdi-mortazavi/relay) خودکارش کن.

## وقتی روی گوشی وی‌پی‌ان روشن است

رله هر اتصالی که گوشی دارد را به اشتراک می‌گذارد — **از جمله وی‌پی‌انش** — و مسیر را
خودش انتخاب می‌کند و هر ده ثانیه دوباره بررسی می‌کند:

| برنامهٔ وی‌پی‌انت… | چه کنی |
|---|---|
| ترافیک محلی را رد می‌کند (بیشترشان) | هیچ کاری. کار می‌کند. |
| گزینهٔ **«دسترسی شبکهٔ محلی» / LAN access** دارد | روشنش کن. |
| **فهرست برنامه‌ها** یا **حالت فقط-پروکسی** دارد | رله را مستثنا کن یا روی فقط-پروکسی بگذار، بعد وی‌پی‌ان را یک بار خاموش و روشن کن. رله پروکسی محلی وی‌پی‌ان را خودش پیدا می‌کند و کامپیوتر همچنان وی‌پی‌ان را می‌گیرد. |

**محدودیت واقعی:** وی‌پی‌انی که هیچ‌کدام از این سه گزینه را نداشته باشد قابل اشتراک
نیست — اندروید به برنامهٔ دیگری اجازهٔ دور زدنش را نمی‌دهد. اگر یک وی‌پی‌ان جواب نداد،
معمولاً حالت فقط-پروکسی‌اش یا یک وی‌پی‌ان دیگر جواب می‌دهد. [جزئیات کامل](docs/vpn-compat.md).

## چه چیزهایی می‌گیری

- **همهٔ برنامه‌های کامپیوتر** — یک تونل WireGuard که هم TCP و هم UDP را می‌برد؛ پس بازی، تماس تصویری و نصب‌کننده‌ها هم کار می‌کنند، نه فقط مرورگر.
- **وضعیت صادقانه** — «متصل» یعنی دست‌دادن واقعی انجام شده. گوشی سرعت لحظه‌ای را نشان می‌دهد؛ کامپیوتر سرعت، حجم و تأخیر را.
- **پایدار** — اگر نشانی گوشی عوض شود دنبالش می‌رود و از افت بسته و رفتن از وای‌فای به LTE رد می‌شود.
- **جلوگیری از نشت** — DNS و IPv6 نمی‌توانند از کنار تونل بیرون بروند، با هزینهٔ ۲۲ میکروثانیه برای هر اتصال. **کلید قطع اضطراری** اختیاری هم اگر گوشی جواب ندهد اینترنت را می‌بندد.
- **همیشه در دسترس** — کاشی تنظیمات سریع، ویجت صفحهٔ اصلی با کد، میان‌بر آیکون؛ روی ویندوز برنامه‌ای در سینی سیستم که می‌تواند با ویندوز اجرا شود.
- **فارسی و انگلیسی** در همه‌جا، راست‌به‌چپ درست، و پشتیبانی از صفحه‌خوان روی هر دو پلتفرم.

## حریم خصوصی

- **بدون حساب کاربری، سرور، تله‌متری یا آنالیتیکس.** جایی برای رفتن داده‌هایت وجود ندارد.
- **کلیدها برای هر جفت‌شدن ساخته و با پایان اشتراک‌گذاری نابود می‌شوند.** هیچ‌وقت پخش نمی‌شوند. کامپیوتری که با کد وصل می‌شود تا روی گوشی **اجازه بده** را نزنی چیزی نمی‌گیرد، و گوشی نامش را نشان می‌دهد.
- **هیچ‌چیز بعد از بسته شدن برنامه نمی‌ماند.** رله هیچ تنظیم سراسری را عوض نمی‌کند، پس یک کرش کامپیوترت را خراب نمی‌کند.

## محدودیت‌های فعلی

- در هر نشست فقط یک کامپیوتر، به‌عمد.
- داخل تونل فعلاً فقط IPv4؛ IPv6 بعداً.
- نصب‌کنندهٔ ویندوز فعلاً امضا ندارد؛ امضا کار بعدی [نقشهٔ راه](ROADMAP.md) است.
- آنچه هنوز روی سخت‌افزار واقعی ثابت نشده در [`docs/testing.md`](docs/testing.md) نوشته شده، نه پنهان.

## برای توسعه‌دهنده‌ها

</div>

```
android/   Kotlin + Compose            windows/   .NET 8 + WinUI 3
wg/        WireGuard endpoint (Go)     shared/    the contract both apps are tested against
```

<div dir="rtl">

**مرحلهٔ بیلد محلی وجود ندارد** — CI خودش سیستم بیلد است ([ADR-0004](docs/adr/0004-github-only-build-and-release.md)).
یک برنچ push کن تا بیلد شود، روی ایمیج‌های واقعی اندروید و یک رانر واقعی ویندوز تست
شود و قابل انتشار باشد. پیش از اولین PR: اول `/shared` را تغییر بده، هر متن را به
فارسی و انگلیسی بنویس، و [`docs/architecture.md`](docs/architecture.md) و
[`CONTRIBUTING.md`](CONTRIBUTING.md) را بخوان.

</div>

<br>

<div align="center">

<img src="https://avatars.githubusercontent.com/u/127998145?v=4" width="88" alt="مهدی مرتضوی">

<div dir="rtl">

**مهدی مرتضوی** · ساختمش چون لپ‌تاپم مدام اینترنت نداشت و گوشی‌ام داشت.<br>
<sub>اگر رله به کارت آمد، یک ⭐ کمکش می‌کند. اگر خراب شد، [بگو](https://github.com/Mahdi-mortazavi/relay/issues/new/choose) — همه‌چیز را می‌خوانم.</sub>

</div>

[![Telegram](https://img.shields.io/badge/Telegram-@Mahdi__mortazavi1-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/Mahdi_mortazavi1)
[![Community](https://img.shields.io/badge/Community-Startup_Legend-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/Startup_legend)
[![Email](https://img.shields.io/badge/Email-Get_in_touch-EA4335?style=flat-square&logo=gmail&logoColor=white)](mailto:Mahdi.mortazavi.135@gmail.com)
[![Website](https://img.shields.io/badge/Website-mahdi--mortazavi.github.io-4ADFBF?style=flat-square&logo=googlechrome&logoColor=white)](https://mahdi-mortazavi.github.io)

<sub>ساخت ایران · [GPL-3.0](LICENSE) · «WireGuard» علامت تجاری ثبت‌شدهٔ Jason A. Donenfeld است.</sub>

</div>
