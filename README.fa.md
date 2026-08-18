<div align="center">

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/cover-mobile.svg">
  <img src="docs/assets/cover.svg" alt="ریلی — اینترنت گوشی‌ات را با کامپیوترت به اشتراک بگذار. Share your phone's internet with your PC." width="100%">
</picture>

# Relay ⚡

### **اینترنت گوشی‌ات را با کامپیوترت به اشتراک بگذار.**
### **یک کلیک. کل راه‌اندازی همین است.**

<br>

[![دانلود برای ویندوز](https://img.shields.io/badge/دانلود-ویندوز-0078D4?style=for-the-badge&logo=windows&logoColor=white)](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe)
&nbsp;
[![دانلود برای اندروید](https://img.shields.io/badge/دانلود-اندروید-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk)

<sub>همیشه آخرین نسخه · [همه‌ی فایل‌ها و توضیحات](https://github.com/Mahdi-mortazavi/relay/releases/latest)</sub>

<br>

[![CI](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml)
[![آزمون روی دستگاه واقعی](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml)
[![نسخه](https://img.shields.io/github/v/release/Mahdi-mortazavi/relay?sort=semver&color=4ADFBF&label=release)](https://github.com/Mahdi-mortazavi/relay/releases/latest)
[![لایسنس GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![ستاره‌ها](https://img.shields.io/github/stars/Mahdi-mortazavi/relay?style=flat&color=FFD700)](https://github.com/Mahdi-mortazavi/relay/stargazers)

**🌍 [English](README.md) · [فارسی](README.fa.md)**

</div>

---

<div dir="rtl">

لپ‌تاپت اینترنت ندارد، گوشی‌ات دارد. **ریلی آن را جابه‌جا می‌کند** — روی وای‌فای خودت یا هات‌اسپات گوشی، از داخل یک تونل رمزنگاری‌شده‌ی WireGuard، **بدون حساب کاربری، بدون سرور، و بدون اینکه چیزی از این دو دستگاه بیرون برود.**

ریلی یک ابزار رایگان و متن‌باز برای **اشتراک اینترنت گوشی با کامپیوتر** (reverse tethering) از **اندروید به ویندوز** است. تمام برنامه‌های کامپیوتر از مسیر گوشی رد می‌شوند — هم TCP و هم UDP — و **نیازی به روت نیست.**

</div>

---

<div dir="rtl">

## 📥 دانلود

| | فایل | مناسب چه کسی |
|---|---|---|
| **ویندوز** | [**Relay-Setup-x64.exe**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe) | ویندوز ۱۰ و ۱۱. فقط برای کاربر خودت نصب می‌شود. |
| ویندوز ۳۲ بیتی | [Relay-Setup-x86.exe](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x86.exe) | فقط اگر مطمئنی به آن نیاز داری. |
| **اندروید** | [**Relay-android-arm64-v8a.apk**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk) | اندروید ۸ به بالا. تقریباً هر گوشی بعد از ۲۰۱۷. |
| اندروید (همه) | [Relay-android-universal.apk](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-universal.apk) | حجیم‌تر، ولی همه‌جا کار می‌کند. اگر فایل بالا گفت *«برنامه سازگار نیست»* این را بگیر. |
| چک‌سام | [SHA256SUMS.txt](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/SHA256SUMS.txt) | `sha256sum -c SHA256SUMS.txt` |

دو فایل اندروید **یک برنامه‌ی یکسان** از یک بیلد هستند و فقط در معماری CPU فرق دارند. اگر یکی‌شان رفتار متفاوتی داشت، یعنی نسخه‌ی قدیمی است — حذف و دوباره نصب کن.

> **ویندوز موقع اجرای اول هشدار می‌دهد** چون نصب‌کننده هنوز امضای دیجیتال ندارد — **More info ← Run anyway**.
> **اندروید می‌گوید «App not installed»؟** [همه‌ی دلایلی که دیده‌ام این‌جاست](docs/install-troubleshooting.md).

</div>

---

<div dir="rtl">

## 🚀 راه‌اندازی، در حدود پنج ثانیه

**روی گوشی**

۱. هات‌اسپات را روشن کن — یا گوشی و کامپیوتر را روی یک وای‌فای بگذار.
۲. ریلی را باز کن و **Start Sharing** را بزن.
۳. صفحه یک QR و یک **عدد دو رقمی** نشان می‌دهد.

**روی کامپیوتر**

۴. ریلی را باز کن. **گوشی‌ات همین حالا در فهرست هست** — رویش کلیک کن.
۵. روی گوشی **Allow** را بزن.

تمام. کامپیوتر روی اینترنت گوشی است و پنجره نشان می‌دهد چه چیزی در حال عبور است: سرعت لحظه‌ای دانلود و آپلود، مجموع مصرف، پینگ تونل، و مدت اتصال.

لپ‌تاپ دوربین ندارد؟ همان دو رقم به تنهایی کافی است. QR را ترجیح می‌دهی؟ سر جایش هست.

</div>

---

<div dir="rtl">

## ✨ چه چیزی در نسخه‌ی ۲.۰ تازه است

**یک مسیر، که همه‌چیز را عبور می‌دهد.** ریلی قبلاً دو حالت داشت. حالت قدیمی *Fast Mode* یک پروکسی SOCKS5 سراسری بود و فقط TCP را عبور می‌داد — یعنی بازی، تماس، نصب‌کننده‌ها و هر چیزی که از UDP استفاده می‌کرد اصلاً به اشتراک گذاشته نمی‌شد. گزارش همیشگی کاربران تلگرام دسکتاپ بود: مرورگر کار می‌کرد، بقیه نه. آن حالت **حذف شد**، همراه با تنظیم پروکسی سراسری که مجبور بود دست‌کاری کند. حالا یک تونل واقعی WireGuard هست و **تمام برنامه‌ها از داخلش رد می‌شوند.**

**اتصال با دو رقم.** کلیدهای تونل فقط داخل QR بودند، پس لپ‌تاپی که دوربین نداشت اصلاً راهی نداشت. حالا گوشی یک تبادل کوتاه برای جفت‌شدن ارائه می‌دهد که باید توسط شخصِ پشت گوشی تأیید شود — و گوشی‌هایی که در حال اشتراک‌گذاری هستند در همان صفحه‌ی اول کامپیوتر ظاهر می‌شوند، پس معمولاً یک کلیک کل کار است.

**پنجره راست می‌گوید.** «متصل» حالا یعنی یک هند‌شیک واقعی WireGuard انجام شده، نه فقط اینکه یک آداپتور شبکه ساخته شده. اگر گوشی جواب ندهد، ریلی می‌گوید — به‌جای نشان دادن چراغ سبز روی تونلی که یک بایت هم نمی‌تواند عبور دهد.

**گوشی را دنبال می‌کند.** آدرس گوشی یک lease از DHCP است و عوض می‌شود. حالا ریلی وقتی گوشی با آدرس جدید پیدا شود تونل را به آن آدرس منتقل می‌کند، به‌جای اینکه به آدرسی که دیگر وجود ندارد وصل بماند.

<sub>جزئیات کامل در [CHANGELOG](CHANGELOG.md) و [ADR-0009](docs/adr/0009-full-mode-only-and-code-pairing.md).</sub>

</div>

---

<div dir="rtl">

## 🔒 هیچ چیزی از دستگاه‌های تو بیرون نمی‌رود

این بخشی است که بیشتر از همه برایم مهم است، پس یک **قانون** است نه یک ترجیح:

- **بدون حساب کاربری، بدون سرور، بدون تله‌متری، بدون آنالیتیکس.** جایی برای ثبت‌نام نیست و جایی هم برای رفتن داده‌هایت وجود ندارد.
- **تونل، WireGuard است.** کلیدهایش برای هر جفت‌شدن ساخته می‌شوند و با قطع اشتراک‌گذاری از بین می‌روند.
- **کلیدها هرگز پخش نمی‌شوند.** گوشی فقط آن‌قدری اعلام می‌کند که *پیدا شود*. کلیدها از یک تبادل کوتاه عبور می‌کنند که خودت روی صفحه تأیید می‌کنی و آدرس کامپیوتر درخواست‌کننده در آن نوشته شده است.
- **چیزی از پروسه جان سالم به در نمی‌برد.** ریلی ۲.۰ هیچ تنظیم سراسری سیستم را عوض نمی‌کند، پس یک کرش نمی‌تواند دستگاهت را در وضعیت خراب رها کند.

تغییری که یک درخواست شبکه به جایی غیر از گوشی خودت اضافه کند، به دلیل بسیار خوب و یک سند تصمیم مکتوب نیاز دارد. این قانون در [`CLAUDE.md`](CLAUDE.md) و [ADRها](docs/adr/) اجرا می‌شود.

</div>

---

<div dir="rtl">

## 🧪 چطور تست می‌شود

هر کامیت، برنامه‌ی واقعی را روی ایمیج‌های واقعی اندروید (API ۳۰ تا ۳۶) نصب می‌کند و از طریق رابط کاربری خودش پیش می‌برد: شروع اشتراک‌گذاری، نمایش QR، جفت‌شدن یک لپ‌تاپ با کد، پاسخ به درخواست، توقف. کلاینت ویندوز در برابر یک **گوشی زنده** از طریق `adb` اجرا می‌شود. نصب‌کننده‌ی واقعی نصب، اجرا و حذف می‌شود. تونل روی یک آداپتور واقعی WinTun بالا می‌آید و یک هند‌شیک واقعی WireGuard روی آن تأیید می‌شود.

بعد، APK **منتشرشده** روی اندروید ۱۱ تا ۱۶ نصب می‌شود — چون نسخه‌ای که کسی نتواند نصبش کند، نسخه نیست.

**آنچه سخت‌افزار هنوز ثابت نکرده** در [docs/testing.md](docs/testing.md) نوشته شده، نه پنهان. یک پایپ‌لاین که تنها تست معنادارش را رد کرده باشد هم سبز است، پس نام تست‌هایی که اهمیت دارند ثبت شده و اگر یکی بی‌صدا رد شود CI شکست می‌خورد.

</div>

---

<div dir="rtl">

## 🗺️ نقشه‌ی راه

| | |
|:---|:---|
| 🚀 تونل WireGuard، TCP و UDP، همه‌ی برنامه‌ها | ✅ **منتشر شده** |
| 🔢 جفت‌شدن با QR **یا** کد دو رقمی | ✅ **منتشر شده** |
| 📊 سرعت لحظه‌ای، مجموع مصرف، پینگ، نوتیفیکیشن | ✅ **منتشر شده** |
| 🔄 دنبال کردن گوشی هنگام تغییر آدرس | ✅ **منتشر شده** |
| ✍️ نصب‌کننده‌ی امضاشده‌ی ویندوز | 🔨 بعدی |
| 🧩 Split tunnel و مسیریابی هر برنامه جدا | 🔨 بعدی |
| 🐧 کلاینت لینوکس | 💭 خواسته شده — [#74](https://github.com/Mahdi-mortazavi/relay/issues/74) |
| 🍎 کلاینت مک | 💭 بعداً |

<sub>تصویر کامل همراه با دلایل: [**ROADMAP.md**](ROADMAP.md)</sub>

</div>

---

<div dir="rtl">

## 🛠️ برای توسعه‌دهنده‌ها

دو اپلیکیشن و یک قرارداد مشترک.

</div>

```
android/   Kotlin + Compose            windows/   .NET 8 + WinUI 3
wg/        WireGuard endpoint (Go)     shared/    قرارداد بین دو پلتفرم
docs/      معماری، ADRها، تست، خطاها
```

<div dir="rtl">

**هیچ مرحله‌ی بیلد محلی وجود ندارد.** CI همان سیستم بیلد است ([ADR-0004](docs/adr/0004-github-only-build-and-release.md)) — یک برنچ push کن و پایپ‌لاین آن را می‌سازد، روی دستگاه واقعی تست می‌کند و می‌تواند نسخه بدهد. برای مشارکت **نیازی نیست** Android Studio یا .NET SDK یا Go نصب کنی.

</div>

```bash
git clone https://github.com/Mahdi-mortazavi/relay.git
cd relay
git switch -c my-change
# ویرایش کن، کامیت کن، push کن — بقیه‌اش با CI است
```

<div dir="rtl">

**سه چیزی که به‌راحتی و ناخواسته خراب می‌شوند** و بهتر است قبل از اولین PR بدانی:

۱. **اول `/shared` را عوض کن.** فرمت انتقال داده، ماشین حالت و قوانین جفت‌شدن آن‌جا زندگی می‌کنند و هر دو پلتفرم در برابرش تست می‌شوند. عوض کردن یک پلتفرم برای هماهنگ شدن با دیگری، همان راهی است که دو اپ از هم دور می‌افتند.
۲. **هر متنی که کاربر می‌بیند، هم انگلیسی دارد هم فارسی.** این با تست اجرا می‌شود، نه با بازبینی.
۳. **سبز بودن با تست شدن یکی نیست.** اگر تستی اضافه کردی که اهمیت دارد، نامش را به لیست محافظ اضافه کن تا اجرایی که آن را رد کرده قبول نشود.

از [`docs/architecture.md`](docs/architecture.md) و [ADRها](docs/adr/) شروع کن — هر تصمیم مهم همراه با استدلالی که به آن رسیده نوشته شده است. بقیه‌اش در [`CONTRIBUTING.md`](CONTRIBUTING.md) است و issueهایی که نقطه‌ی شروع خوبی هستند با برچسب **`good first issue`** مشخص شده‌اند.

</div>

---

<div align="center">

## 👋 سازنده

<img src="https://avatars.githubusercontent.com/u/127998145?v=4" width="120" style="border-radius:50%" alt="مهدی مرتضوی">

### **مهدی مرتضوی** · Mahdi Mortazavi

**توسعه‌دهنده‌ی فول‌استک × سازنده‌ی محصول × حل‌کننده‌ی مسئله**

<sub>📍 ایران</sub>

<br>

<div dir="rtl">

من مهدی‌ام. دمو نمی‌سازم — چیزی را می‌سازم که خودم لازم داشتم، و بعد آن‌قدر رویش کار می‌کنم تا به‌اندازه‌ای خوب بشود که بتوانم به کسی دیگر بدهمش.

ریلی دقیقاً همین است. از کلافگی خودم شروع شد: لپ‌تاپی که اینترنت نداشت و گوشی‌ای که داشت. حالا روی هر کامیت یک آزمایشگاه دستگاه اجرا می‌کند، روی دو پلتفرم منتشر می‌شود، و درباره‌ی چیزهایی که هنوز نمی‌تواند انجام دهد راستش را می‌گوید. **همین آخری استانداردی است که کارم را با آن می‌سنجم** — ترجیح می‌دهم یک محدودیت را بنویسم تا اینکه خودت کشفش کنی.

اگر ریلی برایت مفید بود، یک ⭐ واقعاً کمک می‌کند. اگر خراب شد، [به من بگو](https://github.com/Mahdi-mortazavi/relay/issues/new/choose) — همه را می‌خوانم.

</div>

<br>

### 💬 در ارتباط باشیم

[![کامیونیتی تلگرام](https://img.shields.io/badge/Community-Startup_Legend-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/Startup_legend)

[![تلگرام](https://img.shields.io/badge/Telegram-@Mahdi__mortazavi1-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/Mahdi_mortazavi1)
&nbsp;
[![ایمیل](https://img.shields.io/badge/Email-Get_in_touch-EA4335?style=for-the-badge&logo=gmail&logoColor=white)](mailto:Mahdi.mortazavi.135@gmail.com)

[![گیت‌هاب](https://img.shields.io/badge/GitHub-@Mahdi--mortazavi-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Mahdi-mortazavi)
&nbsp;
[![وب‌سایت](https://img.shields.io/badge/Website-mahdi--mortazavi.github.io-4ADFBF?style=for-the-badge&logo=googlechrome&logoColor=white)](https://mahdi-mortazavi.github.io)

**🚀 [Startup Legend](https://t.me/Startup_legend)** — کامیونیتی من، جایی که از چیزی که می‌سازم، چیزی که خراب شد و چیزی که از درست کردنش یاد گرفتم می‌نویسم.

<br>

**ساخته شده در ایران 🇮🇷 · [GPL-3.0](LICENSE)**

<sub>«WireGuard» علامت تجاری ثبت‌شده‌ی Jason A. Donenfeld است.</sub>

</div>
