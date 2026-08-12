<div align="center">

<img src="docs/assets/android-idle.png" alt="رله روی اندروید" width="180">
&nbsp;&nbsp;&nbsp;
<img src="docs/assets/windows-idle.png" alt="رله روی ویندوز" width="240">

# رله ⚡

### **اینترنت گوشی‌ات را با کامپیوترت به اشتراک بگذار.**
### **یک QR اسکن کن. تنظیمات همین بود.**

<br>

[![دانلود برای ویندوز](https://img.shields.io/badge/Download-Windows-0078D4?style=for-the-badge)](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe)
&nbsp;
[![دانلود برای اندروید](https://img.shields.io/badge/Download-Android-3DDC84?style=for-the-badge)](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk)

<sub>همیشه جدیدترین نسخه · [همهٔ فایل‌ها و توضیحات](https://github.com/Mahdi-mortazavi/relay/releases/latest)</sub>

<br>

[![CI](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml)
[![E2E device lab](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml)
[![نسخه](https://img.shields.io/github/v/release/Mahdi-mortazavi/relay?sort=semver&color=4ADFBF&label=release)](https://github.com/Mahdi-mortazavi/relay/releases/latest)
[![پروانه](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**🌍 [English](README.md) · [فارسی](README.fa.md)**

</div>

---

## 📥 دانلود

| | فایل | برای چه کسی |
|---|---|---|
| **ویندوز** | [**Relay-Setup-x64.exe**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe) | ویندوز ۱۰ و ۱۱. فقط برای کاربر خودتان نصب می‌شود — بدون نیاز به ادمین. |
| ویندوز ۳۲ بیتی | [Relay-Setup-x86.exe](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x86.exe) | فقط اگر مطمئنید به آن نیاز دارید. |
| **اندروید** | [**Relay-android-arm64-v8a.apk**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk) | اندروید ۸ به بالا. تقریباً هر گوشی از ۲۰۱۷ به بعد. |
| اندروید (هر دستگاهی) | [Relay-android-universal.apk](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-universal.apk) | حجیم‌تر، ولی همه‌جا کار می‌کند. اگر فایل بالا گفت **«برنامه سازگار نیست»**، این را بگیرید. |
| checksum ها | [SHA256SUMS.txt](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/SHA256SUMS.txt) | `sha256sum -c SHA256SUMS.txt` |

ویندوز بار اول هشدار می‌دهد چون هنوز installer امضای کد ندارد — **More info ← Run anyway**. اگر اندروید گفت **«App not installed»**، [این صفحه](docs/install-troubleshooting.md) همهٔ دلایلی را که دیده‌ام پوشش می‌دهد.

---

## 🚀 چطور استفاده کنیم

**روی گوشی**

۱. هات‌اسپات را روشن کنید — یا گوشی و کامپیوتر را روی یک وای‌فای بگذارید.
۲. رله را باز کنید و **Start Sharing** را بزنید.
۳. صفحه یک QR و یک **عدد دو رقمی** نشان می‌دهد.

**روی کامپیوتر**

۴. رله را باز کنید و یا **Scan QR** را بزنید و گوشی را جلوی وبکم بگیرید، یا **Enter Code** را بزنید و همان دو رقم را تایپ کنید.
۵. روی گوشی، درخواستی که ظاهر می‌شود را تأیید کنید.

تمام — مرورگر و بقیهٔ برنامه‌ها از طریق گوشی وصل می‌شوند. آخر کار **Disconnect** را بزنید و ویندوز دقیقاً به حالت قبل برمی‌گردد.

<details>
<summary><b>حالت سریع یا حالت کامل؟</b></summary>

<br>

**حالت سریع** پیش‌فرض است و هیچ اجازهٔ خاصی نمی‌خواهد. TCP را عبور می‌دهد: مرور وب، ویدیو، دانلود و بیشتر برنامه‌ها.

**حالت کامل** هم **TCP و هم UDP** را عبور می‌دهد، پس بازی‌ها و بعضی تماس‌های تصویری هم کار می‌کنند. از WireGuard استفاده می‌کند و ویندوز موقع اتصال **یک بار** اجازه می‌خواهد، چون ساخت آداپتور شبکه بدون آن ممکن نیست. این اجازه فقط به همان پروسهٔ کوچک تونل داده می‌شود، نه به کل برنامه.

انتخاب حالت روی گوشی است، قبل از زدن Start Sharing.

</details>

<details>
<summary><b>اگر مشکلی پیش آمد</b></summary>

<br>

رله تلاش می‌کند به‌جای «اتصال برقرار نشد»، بگوید واقعاً چه اتفاقی افتاده. هر خطا اسم دارد و یک قدم بعدی — فهرست کامل در [docs/errors.md](docs/errors.md).

هر دو برنامه یک لاگ محلی دارند که می‌توانید بخوانید و بفرستید (**Advanced ← Logs**). هیچ‌چیز خودبه‌خود جایی آپلود نمی‌شود.

</details>

---

## 💡 چرا ساختمش

همیشه یک جای مشترک گیر می‌کردم: لپ‌تاپ بدون اینترنت، گوشی با اینترنت. هات‌اسپات ویندوز راه نمی‌افتاد. تترینگ USB درایور می‌خواست. برنامه‌های دیگر اشتراک می‌خواستند، حساب کاربری می‌خواستند، و اجازهٔ دیدن همه‌چیز.

اتصال همیشه همان‌جا بود. مشکل هیچ‌وقت شبکه نبود — راه‌اندازی‌اش بود.

برای همین رله نه حساب کاربری دارد، نه سرور، نه تله‌متری، نه اشتراک. ترافیک شما از گوشی به کامپیوتر روی وای‌فای خودتان می‌رود و به هیچ‌چیز من دست نمی‌زند. **یک کد را اسکن کن و کار می‌کند** — و وقتی کار نکرد، می‌گوید چرا.

---

## 🔐 صادقانه دربارهٔ امنیت

انتقال در حالت سریع یک **پروکسی SOCKS5 بدون رمز** است. این یک انتخاب آگاهانه است: ویندوز نمی‌تواند نام کاربری و رمز پروکسی را در سطح سیستم بدهد، و اجباری کردن رمز همان وعدهٔ «بدون تنظیمات» را می‌شکند که اصلاً دلیل وجود این برنامه است.

پس در عوض: **گوشی شما قبل از اینکه هر کامپیوتری اجازه بگیرد، از خودتان می‌پرسد.** آن عدد دو رقمی فقط گوشی شما را از بین گوشی‌های اطراف انتخاب می‌کند؛ چیزی که غریبه‌ها را بیرون نگه می‌دارد، همان تأیید شماست. حالت کامل نمی‌پرسد، چون آنجا کامپیوتر با کلیدی خودش را اثبات می‌کند که فقط داخل همان QR وجود داشته.

روی هات‌اسپات خودتان امن است. روی وای‌فای مشترک یا عمومی، حالت کامل را ترجیح دهید. مدل تهدید کامل در [SECURITY.md](SECURITY.md) است — از جمله چیزهایی که رله از آن‌ها محافظت **نمی‌کند**.

---

## 🧪 چطور تست می‌شود

هر کامیت، برنامهٔ واقعی را روی ایمیج‌های واقعی اندروید (API ۳۰ تا ۳۶) نصب می‌کند، از داخل UI خودش عبورش می‌دهد و بایت واقعی از پروکسی واقعی رد می‌کند. کد سمت ویندوز مقابل یک **گوشی زنده** روی `adb` اجرا می‌شود. installer واقعی نصب، اجرا و حذف می‌شود و در هر مرحله تنظیمات پروکسی سیستم بررسی می‌شود. تونل حالت کامل روی یک آداپتور واقعی WinTun بالا می‌آید و handshake واقعی WireGuard از رویش تأیید می‌شود.

بعد، APK **منتشرشده** روی اندروید ۱۱ تا ۱۶ نصب می‌شود — چون نسخه‌ای که کسی نتواند نصبش کند، نسخه نیست.

آنچه هنوز فقط سخت‌افزار می‌تواند ثابت کند در [docs/testing.md](docs/testing.md) نوشته شده، نه پنهان.

---

## 🗺️ وضعیت

| | |
|:---|:---|
| ⚡ حالت سریع (SOCKS5، TCP) | ✅ **منتشر شده** |
| 🚀 حالت کامل (WireGuard، TCP + UDP) | ✅ **منتشر شده** — هر دو پلتفرم |
| 🔄 اتصال مجدد خودکار، خطاهای قابل‌اقدام، فارسی و انگلیسی | ✅ **منتشر شده** |
| ✍️ installer امضاشدهٔ ویندوز | 🔨 در برنامه |
| 🍎 کلاینت macOS | 💭 بعداً |

<sub>جزئیات در [`docs/roadmap.md`](docs/roadmap.md) · [CHANGELOG](CHANGELOG.md)</sub>

---

<div align="center">

## 👋 سازنده

<img src="https://avatars.githubusercontent.com/u/127998145?v=4" width="120" style="border-radius:50%" alt="مهدی مرتضوی">

### **مهدی مرتضوی** · Mahdi Mortazavi

**توسعه‌دهندهٔ Full-Stack × سازندهٔ محصول × حل‌کنندهٔ مسئله**

<sub>📍 ایران</sub>

<br>

من مهدی‌ام. دمو نمی‌سازم — چیزی را می‌سازم که خودم لازم داشته‌ام، و آن‌قدر رویش کار می‌کنم تا به درد دست دیگران هم بخورد.

رله دقیقاً همین است. از سرخوردگی خودم شروع شد: لپ‌تاپی بدون اینترنت و گوشی‌ای با اینترنت. حالا روی هر کامیت یک آزمایشگاه دستگاه واقعی اجرا می‌کند، روی دو پلتفرم منتشر می‌شود، و دربارهٔ کارهایی که هنوز بلد نیست راستش را می‌گوید. **همین قسمت آخر، معیار من برای کارم است** — ترجیح می‌دهم یک محدودیت را بنویسم تا اینکه شما خودتان کشفش کنید.

اگر رله به دردتان خورد، یک ⭐ واقعاً کمک می‌کند. اگر خراب شد، [به من بگویید](https://github.com/Mahdi-mortazavi/relay/issues/new/choose) — همه را می‌خوانم.

<br>

### 💬 در ارتباط باشیم

[![کامیونیتی تلگرام](https://img.shields.io/badge/Community-Startup_Legend-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/Startup_legend)

[![تلگرام](https://img.shields.io/badge/Telegram-@Mahdi__mortazavi1-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/Mahdi_mortazavi1)
&nbsp;
[![ایمیل](https://img.shields.io/badge/Email-Get_in_touch-EA4335?style=for-the-badge&logo=gmail&logoColor=white)](mailto:Mahdi.mortazavi.135@gmail.com)

[![گیت‌هاب](https://img.shields.io/badge/GitHub-@Mahdi--mortazavi-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Mahdi-mortazavi)
&nbsp;
[![وب‌سایت](https://img.shields.io/badge/Website-mahdi--mortazavi.github.io-4ADFBF?style=for-the-badge&logo=googlechrome&logoColor=white)](https://mahdi-mortazavi.github.io)

**🚀 [Startup Legend](https://t.me/Startup_legend)** — کامیونیتی من، جایی که می‌نویسم چه می‌سازم، چه چیزی خراب شد و از درست کردنش چه یاد گرفتم.

</div>

---

## 🛠️ برای توسعه‌دهنده‌ها

دو برنامه و یک قرارداد مشترک. اندروید با Kotlin و Compose؛ ویندوز با ‎.NET 8‎ و WinUI 3؛ تونل حالت کامل با Go (`wg/`) که هر دو سر از آن استفاده می‌کنند.

**نیازی به build محلی نیست** — CI خودش سیستم build است (ADR-0004). push کنید و pipeline می‌سازد، روی دستگاه واقعی تست می‌کند و می‌تواند نسخه منتشر کند.

```
android/   Kotlin + Compose            windows/   .NET 8 + WinUI 3
wg/        endpoint وایرگارد (Go)      shared/    قرارداد بین دو پلتفرم
docs/      معماری، ADR ها، تست، خطاها
```

از [`docs/architecture.md`](docs/architecture.md) و [ADR ها](docs/adr/) شروع کنید — هر تصمیم مهم، همراه با دلیلی که به آن رسیده، نوشته شده است. بقیه‌اش در [`CONTRIBUTING.md`](CONTRIBUTING.md) است.

<div align="center">

<br>

**ساخته‌شده در ایران 🇮🇷 · [Apache-2.0](LICENSE)**

<sub>«WireGuard» علامت تجاری ثبت‌شدهٔ Jason A. Donenfeld است.</sub>

</div>
