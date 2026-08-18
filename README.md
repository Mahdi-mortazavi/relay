<div align="center">

<img src="docs/assets/cover.svg" alt="Relay — share your phone's internet with your PC. اینترنت گوشی‌ات را با کامپیوترت به اشتراک بگذار." width="100%">

# Relay ⚡

### **Share your phone's internet with your PC.**
### **Click one row. That's the entire setup.**

<br>

[![Download for Windows](https://img.shields.io/badge/Download-Windows-0078D4?style=for-the-badge&logo=windows&logoColor=white)](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe)
&nbsp;
[![Download for Android](https://img.shields.io/badge/Download-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk)

<sub>Always the newest release · [all files & notes](https://github.com/Mahdi-mortazavi/relay/releases/latest)</sub>

<br>

[![CI](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml)
[![E2E device lab](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml)
[![Release](https://img.shields.io/github/v/release/Mahdi-mortazavi/relay?sort=semver&color=4ADFBF&label=release)](https://github.com/Mahdi-mortazavi/relay/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Stars](https://img.shields.io/github/stars/Mahdi-mortazavi/relay?style=flat&color=FFD700)](https://github.com/Mahdi-mortazavi/relay/stargazers)

**🌍 [English](README.md) · [فارسی](README.fa.md)**

</div>

---

Your laptop has no internet. Your phone does. **Relay moves it across** — over your own Wi-Fi or the phone's hotspot, through an encrypted WireGuard tunnel, with **no account, no server, and nothing leaving your two devices**.

It is a free, open-source **reverse tethering** and **internet sharing** tool for **Android → Windows**. Every application on the PC goes through the phone — TCP *and* UDP — and **no root is required**.

---

## 📥 Download

| | File | Who it's for |
|---|---|---|
| **Windows** | [**Relay-Setup-x64.exe**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe) | Windows 10/11. Installs for you alone. |
| Windows 32-bit | [Relay-Setup-x86.exe](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x86.exe) | Only if you know you need it. |
| **Android** | [**Relay-android-arm64-v8a.apk**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk) | Android 8.0+. Almost every phone since 2017. |
| Android (any) | [Relay-android-universal.apk](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-universal.apk) | Bigger, works everywhere. Use this if the one above says *"app not compatible"*. |
| Checksums | [SHA256SUMS.txt](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/SHA256SUMS.txt) | `sha256sum -c SHA256SUMS.txt` |

The two Android files are the **same app** from the same build, differing only in which CPU architectures they carry. If one seems to behave differently, it is an older copy — uninstall and reinstall.

> **Windows warns on first run** because the installer isn't code-signed yet — choose **More info → Run anyway**.
> **Android says "App not installed"?** [Every cause I have seen is on this page](docs/install-troubleshooting.md).

---

## 🚀 Setup, in about five seconds

**On your phone**

1. Turn on your hotspot — or put the phone and the PC on the same Wi-Fi.
2. Open Relay and tap **Start Sharing**.
3. The screen shows a QR code and a **two-digit number**.

**On your PC**

4. Open Relay. **Your phone is already in the list** — click it.
5. Tap **Allow** on the phone.

That is the whole thing. The PC is now on your phone's connection, and the window shows what it is carrying: live download and upload speed, totals, tunnel latency, and how long you have been connected.

No camera on the laptop? The two digits work on their own. Prefer the QR? It is still there.

---

## ✨ What's new in 2.0

**One transport, and it carries everything.** Relay used to have two modes. The old *Fast Mode* was a system-wide SOCKS5 proxy, and it only ever carried TCP — so games, calls, installers and anything using UDP were never really shared. Telegram Desktop was the usual report: the browser worked, nothing else did. That mode is **gone**, along with the machine-wide proxy setting it had to change. There is now one real WireGuard tunnel, and **every application goes through it**.

**Pair with two digits.** The tunnel's keys only ever lived inside the QR code, so a laptop without a camera had no way in at all. Now the phone offers a short pairing exchange that the person holding the phone has to allow — and phones that are already sharing appear on the PC's first screen, so one click is usually the entire setup.

**The window tells the truth.** "Connected" now means a real WireGuard handshake completed, not merely that a network adapter exists. If the phone stops answering, Relay says so instead of showing a green dot over a tunnel that cannot carry a byte.

**It follows your phone.** A phone's address is a DHCP lease, and leases change. Relay now re-points the tunnel when the phone reappears at a new address, instead of dialling one that has moved.

<sub>Full detail in the [CHANGELOG](CHANGELOG.md) and in [ADR-0009](docs/adr/0009-full-mode-only-and-code-pairing.md).</sub>

---

## 🔒 Nothing leaves your devices

This is the part I care most about, so it is a rule rather than a preference:

- **No accounts, no servers, no telemetry, no analytics.** There is nothing to sign up for, and nowhere for your data to go.
- **The tunnel is WireGuard.** Its keys are minted per pairing and destroyed when sharing stops.
- **Keys are never broadcast.** The phone announces only enough to be *found*. The keys travel over a short exchange that you approve on screen, with the requesting computer's address shown.
- **Nothing outlives the process.** Relay 2.0 changes no system-wide setting, so a crash cannot strand your machine in a broken state.

A change that adds a network call to anything but your own phone needs a very good reason and a written decision record. That rule is enforced in [`CLAUDE.md`](CLAUDE.md) and in the [ADRs](docs/adr/).

---

## 🧪 How it's tested

Every commit installs the real app on real Android images (API 30–36) and drives it through its own UI: start sharing, show a QR, let a laptop pair by code, answer the prompt, stop. The Windows client runs against a **live phone** over `adb`. The real installer is installed, launched and uninstalled. The tunnel is brought up on a real WinTun adapter, and a real WireGuard handshake is verified across it.

Then the *published* APK is installed on Android 11 through 16 — because a release nobody can install is not a release.

**What hardware still has not proved is written down** in [docs/testing.md](docs/testing.md) rather than hidden. A pipeline that skipped its only meaningful test is also green, so the test names that matter are pinned and CI fails if one silently skips.

---

## 🗺️ Roadmap

| | |
|:---|:---|
| 🚀 WireGuard tunnel, TCP + UDP, every app | ✅ **Shipping** |
| 🔢 Pair by QR **or** two-digit code | ✅ **Shipping** |
| 📊 Live speed, totals, latency, notification | ✅ **Shipping** |
| 🔄 Follows the phone across address changes | ✅ **Shipping** |
| ✍️ Signed Windows installer | 🔨 Next |
| 🧩 Split tunnelling / per-app routing | 🔨 Next |
| 🐧 Linux client | 💭 Wanted — [#74](https://github.com/Mahdi-mortazavi/relay/issues/74) |
| 🍎 macOS client | 💭 Later |

<sub>The full picture, with reasoning: [**ROADMAP.md**](ROADMAP.md)</sub>

---

## 🛠️ For developers

Two apps and one shared contract.

```
android/   Kotlin + Compose            windows/   .NET 8 + WinUI 3
wg/        WireGuard endpoint (Go)     shared/    the cross-platform contract
docs/      architecture, ADRs, testing, errors
```

**There is no local build step.** CI is the build system ([ADR-0004](docs/adr/0004-github-only-build-and-release.md)) — push a branch and the pipeline builds it, tests it on real devices, and can cut a release. You do **not** need Android Studio, the .NET SDK or Go installed to contribute.

```bash
git clone https://github.com/Mahdi-mortazavi/relay.git
cd relay
git switch -c my-change
# edit, commit, push — CI builds and tests it for you
```

**Three things that are easy to break by accident**, worth knowing before your first PR:

1. **Change `/shared` first.** The wire format, the state machine and the pairing rules live there, and both platforms are asserted against it. Editing one platform to match the other is how the two apps drift.
2. **Every user-facing string exists in English *and* Persian.** This is enforced by tests, not by review.
3. **Green is not the same as tested.** If you add a test that matters, add its name to the guard, so a run that silently skipped it cannot pass.

Start with [`docs/architecture.md`](docs/architecture.md) and the [ADRs](docs/adr/) — every significant decision is written down together with the reasoning that produced it. [`CONTRIBUTING.md`](CONTRIBUTING.md) has the rest, and issues that are a good place to start are labelled **`good first issue`**.

---

<div align="center">

## 👋 Meet the developer

<img src="https://avatars.githubusercontent.com/u/127998145?v=4" width="120" style="border-radius:50%" alt="Mahdi Mortazavi">

### **Mahdi Mortazavi** · مهدی مرتضوی

**Full-Stack Developer × Product Builder × Problem Solver**

<sub>📍 Iran</sub>

<br>

I'm Mahdi. I don't build demos — I build the thing I needed, then keep working on it until it is good enough to hand to someone else.

Relay is exactly that. It began as my own frustration with a laptop that had no internet and a phone that did. Now it runs a device lab on every commit, ships on two platforms, and tells you the truth about what it cannot do yet. **That last part is the standard I hold my work to** — I would rather write down a limitation than let you discover it.

If Relay is useful to you, a ⭐ genuinely helps. If it breaks, [tell me](https://github.com/Mahdi-mortazavi/relay/issues/new/choose) — I read everything.

<br>

### 💬 Let's talk

[![Telegram Community](https://img.shields.io/badge/Community-Startup_Legend-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/Startup_legend)

[![Telegram](https://img.shields.io/badge/Telegram-@Mahdi__mortazavi1-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/Mahdi_mortazavi1)
&nbsp;
[![Email](https://img.shields.io/badge/Email-Get_in_touch-EA4335?style=for-the-badge&logo=gmail&logoColor=white)](mailto:Mahdi.mortazavi.135@gmail.com)

[![GitHub](https://img.shields.io/badge/GitHub-@Mahdi--mortazavi-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Mahdi-mortazavi)
&nbsp;
[![Website](https://img.shields.io/badge/Website-mahdi--mortazavi.github.io-4ADFBF?style=for-the-badge&logo=googlechrome&logoColor=white)](https://mahdi-mortazavi.github.io)

**🚀 [Startup Legend](https://t.me/Startup_legend)** — my community, where I share what I am building, what broke, and what I learned fixing it.

<br>

**Made in Iran 🇮🇷 · [GPL-3.0](LICENSE)**

<sub>"WireGuard" is a registered trademark of Jason A. Donenfeld.</sub>

</div>
