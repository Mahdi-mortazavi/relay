# Roadmap

**🌍 English · [فارسی below](#نقشهی-راه)**

This is what Relay is going to do next, and — more usefully — *why*, and what
would have to be true first. Nothing here is a promise with a date on it. It is
a public statement of priority, so you can tell whether the thing you need is
coming, is possible, or has been ruled out.

If something you want is missing, [open an issue](https://github.com/Mahdi-mortazavi/relay/issues/new/choose).
Requests move things up this list more often than you would think.

---

## ✅ Shipping now — 2.0

| | What it means for you |
|---|---|
| **WireGuard tunnel, TCP + UDP** | Every application on the PC goes through the phone. Games, calls, installers — not only the browser. |
| **Pair by QR or two digits** | A laptop with no camera can still connect. Phones already sharing appear on the first screen, so it is usually one click. |
| **Approval on the phone** | Keys are only handed out after the person holding the phone allows it, with the requesting computer's address shown. |
| **Live statistics** | Download and upload speed, totals, tunnel latency, connection duration — read from the adapter, so they cannot drift from reality. |
| **Honest connection state** | "Connected" means a real handshake completed. If the phone stops answering, Relay says so. |
| **Follows a moving phone** | When the phone's address changes, the tunnel is re-pointed instead of dying. |
| **English + Persian** | Every user-facing string, enforced by tests. |

---

## 🔨 Next

### Signed Windows installer

**Why it matters:** Windows shows a scary warning on first run, and a fair
number of people stop there. It is the single biggest thing standing between
Relay and someone who has never heard of it.

**What has to happen:** a code-signing certificate, and the release pipeline
teaching to use it. This is a cost-and-paperwork problem, not a technical one.

### Split tunnelling / per-app routing

**Why it matters:** right now the tunnel is all or nothing. If you want only
your browser on the phone's connection while everything else uses the laptop's
own, you cannot have it.

**What has to happen:** the Windows client has to install routes for a subset of
traffic, and the UI has to make "which apps" a question a person can answer
without reading documentation. Tracked in [`docs/backlog.md`](docs/backlog.md).

### Sharing a phone that is itself on a VPN

**Why it matters:** this is the most-reported limitation, and it is a real
conflict rather than an oversight. Android routes by UID, and a full-tunnel VPN
claims Relay's UID, so the tunnel's own traffic gets swallowed by the VPN. Relay
**detects this today and tells you**, but detecting is not fixing.

**What has to happen:** forwarded traffic has to go out through the VPN app's
own local proxy rather than through Relay's socket. The approach is understood;
the work is not done. See [`docs/vpn-compat.md`](docs/vpn-compat.md).

---

## 💭 Wanted, not scheduled

### Linux client — [#74](https://github.com/Mahdi-mortazavi/relay/issues/74)

The phone side is already platform-agnostic: it speaks WireGuard and a small
documented pairing protocol, both written down in [`/shared`](shared/). A Linux
client is genuinely tractable — it is a matter of someone building the client
half, and of my having a Linux machine to test it on honestly. **This is the
single best place for a contributor to make a large difference.**

### macOS client

Same story as Linux, with the added cost of Apple's signing and notarisation.

### IPv6

The tunnel is IPv4 today. Not hard, not yet prioritised, and it needs real
hardware to prove rather than a runner.

---

## 🚫 Ruled out, on purpose

These are not "not yet". They are decisions, and reversing one needs a written
argument that beats the reasoning that produced it.

| | Why not |
|---|---|
| **Accounts, servers, cloud sync** | Nothing leaves your two devices. Any change that adds a network call to anything but your own phone needs an ADR. |
| **Telemetry or analytics** | Same rule. I would rather read your issue than your data. |
| **A system-wide proxy mode** | This was Fast Mode, and it is removed. It carried TCP only, and a stranded proxy breaks every application on the machine. [ADR-0009](docs/adr/0009-full-mode-only-and-code-pairing.md). |
| **Requiring root on the phone** | A tool most people cannot run is not a tool. |
| **Multiple PCs on one sharing session** | The endpoint is configured with a single peer. Supporting several is possible but changes the security model, so it needs a real reason. |

---

## 🧭 How this list is decided

In roughly this order:

1. **Anything that makes Relay lie.** A wrong state on screen outranks a missing
   feature, always.
2. **Anything that stops someone connecting at all.** The signing warning, the
   install failures, the VPN conflict.
3. **Anything hardware has not proved.** [`docs/testing.md`](docs/testing.md)
   is the list, and it is public for exactly this reason.
4. **New capability.**

---

<div dir="rtl">

# نقشه‌ی راه

این‌جا نوشته‌ام که ریلی بعداً چه کار می‌کند، و مهم‌تر از آن **چرا**، و چه چیزی باید
اول درست شود. هیچ‌کدام از این‌ها قولِ تاریخ‌دار نیست. این یک اعلام عمومی از
اولویت‌هاست تا بتوانی بفهمی چیزی که لازم داری در راه است، ممکن است، یا عمداً کنار
گذاشته شده.

اگر چیزی که می‌خواهی این‌جا نیست، [یک issue باز کن](https://github.com/Mahdi-mortazavi/relay/issues/new/choose).
درخواست‌ها بیشتر از چیزی که فکر کنی جایگاه چیزها را در این فهرست بالا می‌برند.

## ✅ همین حالا منتشر شده — نسخه‌ی ۲.۰

- **تونل WireGuard با TCP و UDP** — همه‌ی برنامه‌های کامپیوتر از گوشی رد می‌شوند، نه فقط مرورگر.
- **جفت‌شدن با QR یا دو رقم** — لپ‌تاپ بدون دوربین هم وصل می‌شود؛ معمولاً یک کلیک کافی است.
- **تأیید روی گوشی** — کلیدها فقط بعد از اجازه‌ی شخصِ پشت گوشی داده می‌شوند.
- **آمار زنده** — سرعت، مجموع مصرف، پینگ تونل و مدت اتصال.
- **وضعیت صادقانه** — «متصل» یعنی هند‌شیک واقعی انجام شده.
- **دنبال کردن گوشی** — با تغییر آدرس گوشی، تونل منتقل می‌شود.
- **فارسی و انگلیسی** — هر متنی که کاربر می‌بیند، با تست تضمین شده.

## 🔨 بعدی

**نصب‌کننده‌ی امضاشده‌ی ویندوز** — هشدار ترسناک ویندوز بزرگ‌ترین مانع بین ریلی و
کسی است که تا حالا اسمش را نشنیده. این مسئله‌ی هزینه و کاغذبازی است، نه فنی.

**Split tunnel** — الان تونل همه‌یاهیچ است. اگر بخواهی فقط مرورگرت از گوشی رد شود،
نمی‌شود.

**اشتراک گوشی‌ای که خودش روی VPN است** — پرگزارش‌ترین محدودیت. اندروید بر اساس UID
مسیریابی می‌کند و VPN تمام‌تونل، UID ریلی را هم می‌گیرد. ریلی **امروز این را
تشخیص می‌دهد و به تو می‌گوید**، ولی تشخیص با حل کردن فرق دارد.

## 💭 خواسته شده، ولی زمان‌بندی ندارد

**کلاینت لینوکس** ([#74](https://github.com/Mahdi-mortazavi/relay/issues/74)) —
سمت گوشی از قبل مستقل از پلتفرم است و پروتکل در [`/shared`](shared/) مستند شده.
**این بهترین جا برای یک مشارکت‌کننده است که تفاوت بزرگی ایجاد کند.**

**کلاینت مک** — مثل لینوکس، به‌علاوه‌ی هزینه‌ی امضا و notarization اپل.

**IPv6** — سخت نیست، ولی هنوز اولویت نگرفته و برای اثباتش سخت‌افزار واقعی لازم است.

## 🚫 عمداً کنار گذاشته شده

- **حساب کاربری، سرور، همگام‌سازی ابری** — هیچ چیزی از دو دستگاه تو بیرون نمی‌رود.
- **تله‌متری و آنالیتیکس** — ترجیح می‌دهم issue تو را بخوانم، نه داده‌ات را.
- **حالت پروکسی سراسری** — همان Fast Mode بود و حذف شد؛ فقط TCP را عبور می‌داد و یک پروکسی رهاشده کل سیستم را خراب می‌کند.
- **نیاز به روت** — ابزاری که اکثر مردم نتوانند اجرایش کنند، ابزار نیست.
- **چند کامپیوتر روی یک نشست** — مدل امنیتی را عوض می‌کند و به دلیل واقعی نیاز دارد.

</div>
