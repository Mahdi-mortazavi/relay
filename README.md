<div align="center">

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/cover-mobile.svg">
  <img src="docs/assets/cover.svg" alt="Relay — share your phone's internet with your PC over an encrypted WireGuard tunnel. اینترنت گوشی‌ات را با کامپیوترت به اشتراک بگذار." width="100%">
</picture>

<br>

[<img src="docs/assets/btn-windows.svg" alt="Download for Windows" height="46">](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe)
&nbsp;
[<img src="docs/assets/btn-android.svg" alt="Download for Android" height="46">](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk)
&nbsp;
[<img src="docs/assets/btn-film.svg" alt="Watch the film" height="46">](#watch-it-work)
&nbsp;
[<img src="docs/assets/btn-guide-fa.svg" alt="راهنمای فارسی" height="46">](README.fa.md)

[![CI](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml)
[![Device lab](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml)
[![Release](https://img.shields.io/github/v/release/Mahdi-mortazavi/relay?sort=semver&color=4ADFBF&label=release)](https://github.com/Mahdi-mortazavi/relay/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-4ADFBF)](LICENSE)

**Share your phone's internet with your PC — every app, TCP and UDP, over an encrypted WireGuard tunnel.**<br>
No root · No account · No server · Nothing leaves your two devices

</div>

<br>

## Three steps

<div align="center">

| 1 · Phone | 2 · PC | 3 · Phone |
|:---:|:---:|:---:|
| Tap **Start Sharing** | Click your phone in the list<br><sub>or type the two digits it shows</sub> | Tap **Allow** |

</div>

Use whatever already connects the two: **your Wi-Fi**, the **phone's hotspot**,
or a **USB cable**. With a cable plugged in, Relay offers to turn USB tethering on.
No camera on the laptop is fine; no list means you can scan the QR instead.

<div align="center">

<img src="docs/assets/android-sharing.png" alt="Relay on Android, sharing: the two-digit pairing code and a QR code" width="260">
&nbsp;&nbsp;&nbsp;
<img src="docs/assets/windows-idle.png" alt="Relay on Windows: a compact tray window offering to scan a QR or enter the code" width="300">

</div>

## Watch it work

Sixty seconds, no narration: the two digits, the PC coming online, and a download
that keeps running while the phone moves from Wi-Fi to LTE underneath it.

<div align="center">

<img src="docs/assets/relay-film.gif" alt="Relay pairing and coming online: the phone shows a two-digit code, the PC moves from Ready to connect to online, and a download keeps running while the phone switches from Wi-Fi to LTE" width="760">

</div>

https://github.com/user-attachments/assets/fcfbc7ec-e61f-48c0-96b8-cd4713abb49b

<sub>The loop is drawn; the video above has sound. For the unedited real thing, **[KASRA MAX's four-minute walkthrough](https://raw.githubusercontent.com/Mahdi-mortazavi/relay/main/docs/assets/relay-demo-kasra.mp4)** goes from install to browsing (Persian narration, English subtitles) — thank you, Kasra, for that and for the bugs you found.</sub>

## Download

| | File | For |
|---|---|---|
| **Windows** | [**Relay-Setup-x64.exe**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe) | Windows 10/11 · installs for you alone, no admin |
| **Android** | [**Relay-android-arm64-v8a.apk**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk) | Android 8+ · almost every phone since 2017 |
| Android, any CPU | [Relay-android-universal.apk](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-universal.apk) | If the one above says *App not installed* |
| Windows 32-bit | [Relay-Setup-x86.exe](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x86.exe) | Only if you know you need it |

- **Windows warns on first run** — the installer isn't code-signed yet: **More info → Run anyway**. Every release carries [`SHA256SUMS.txt`](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/SHA256SUMS.txt) to check it.
- **"App not installed" on Android?** [Here is every cause seen so far](docs/install-troubleshooting.md).
- **Updates:** Windows updates itself when you're not connected. Android offers each update (the platform never allows a silent one), or use [**Obtainium**](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Mahdi-mortazavi/relay) to install them automatically.

## With a VPN on the phone

Relay shares whatever the phone is connected to — **including its VPN** — and
picks the route by itself, re-checking every ten seconds:

| Your VPN app… | What to do |
|---|---|
| Lets local traffic through (most do) | Nothing. It just works. |
| Has **"local network access" / "LAN access"** | Turn it on. |
| Has a **per-app list** or a **proxy-only mode** | Exclude Relay, or switch to proxy-only, then turn the VPN off and on. Relay finds the VPN's local proxy and the PC still gets the VPN. |

**The honest limit:** a VPN with none of those three options can't be shared —
Android gives other apps no way around it. If one VPN doesn't work, its
proxy-only mode or another VPN app usually will. [The full story](docs/vpn-compat.md).

## What you get

- **Every app on the PC** — one WireGuard tunnel carrying TCP *and* UDP, so games, calls and installers work, not just the browser.
- **Honest status** — "Connected" means a real handshake happened. The phone shows live speed; the PC shows speed, totals and latency.
- **Keeps going** — follows the phone to a new address and rides out packet loss and a switch from Wi-Fi to LTE.
- **Leak protection** — DNS and IPv6 can't slip around the tunnel, at 22 µs per connection. An optional **kill switch** blocks the internet if the phone stops answering.
- **One tap away** — Quick Settings tile, home-screen widget with the code, launcher shortcut; on Windows a tray app that can start with the system.
- **English and فارسی** throughout, right-to-left done properly, with screen-reader support on both platforms.

## Privacy

- **No accounts, no servers, no telemetry, no analytics.** There is nowhere for your data to go.
- **Keys are made per pairing and destroyed when sharing stops.** They are never broadcast. A computer pairing by code gets nothing until you tap **Allow** on the phone, which shows its name.
- **Nothing outlives the app.** Relay changes no system-wide setting, so a crash cannot leave your PC broken.

Adding any network call beyond your own phone needs a written decision record — see [`CLAUDE.md`](CLAUDE.md) and the [ADRs](docs/adr/).

## Known limits

- One PC per sharing session, by design.
- IPv4 inside the tunnel; IPv6 is later.
- The Windows installer is unsigned for now; signing is next on the [roadmap](ROADMAP.md).
- What real hardware hasn't proved yet is listed in [`docs/testing.md`](docs/testing.md), not hidden.

## For developers

```
android/   Kotlin + Compose            windows/   .NET 8 + WinUI 3
wg/        WireGuard endpoint (Go)     shared/    the contract both apps are tested against
```

**There is no local build step** — CI is the build system ([ADR-0004](docs/adr/0004-github-only-build-and-release.md)).
Push a branch and it is built, tested on real Android images and a real Windows
runner, and can be released. Before your first PR: change `/shared` first, write
every string in English and Persian, and read [`docs/architecture.md`](docs/architecture.md)
and [`CONTRIBUTING.md`](CONTRIBUTING.md).

<br>

<div align="center">

<img src="https://avatars.githubusercontent.com/u/127998145?v=4" width="88" alt="Mahdi Mortazavi">

**Mahdi Mortazavi** · built because a laptop kept having no internet while the phone did.<br>
<sub>If Relay helps you, a ⭐ helps it. If it breaks, [tell me](https://github.com/Mahdi-mortazavi/relay/issues/new/choose) — I read everything.</sub>

[![Telegram](https://img.shields.io/badge/Telegram-@Mahdi__mortazavi1-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/Mahdi_mortazavi1)
[![Community](https://img.shields.io/badge/Community-Startup_Legend-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/Startup_legend)
[![Email](https://img.shields.io/badge/Email-Get_in_touch-EA4335?style=flat-square&logo=gmail&logoColor=white)](mailto:Mahdi.mortazavi.135@gmail.com)
[![Website](https://img.shields.io/badge/Website-mahdi--mortazavi.github.io-4ADFBF?style=flat-square&logo=googlechrome&logoColor=white)](https://mahdi-mortazavi.github.io)

<sub>Made in Iran · [GPL-3.0](LICENSE) · "WireGuard" is a registered trademark of Jason A. Donenfeld.</sub>

</div>
