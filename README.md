<div align="center">

<picture>
  <source media="(max-width: 640px)" srcset="docs/assets/cover-mobile.svg">
  <img src="docs/assets/cover.svg" alt="Relay — share your phone's internet with your PC over an encrypted WireGuard tunnel. اینترنت گوشی‌ات را با کامپیوترت به اشتراک بگذار." width="100%">
</picture>

<br><br>

**Your laptop has no internet. Your phone does. Relay moves it across.**

Reverse tethering for Android → Windows, over an encrypted WireGuard tunnel.
No root, no account, no server.

<br>

[![Download for Windows](https://img.shields.io/badge/Download-Windows-0078D4?style=for-the-badge&logo=windows&logoColor=white)](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe)
&nbsp;
[![Download for Android](https://img.shields.io/badge/Download-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk)

<sub>
  <a href="https://github.com/Mahdi-mortazavi/relay/releases/latest">Latest release</a> ·
  <a href="README.fa.md">فارسی</a> ·
  <a href="LICENSE">GPL-3.0</a>
</sub>

<br>

[![CI](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml)
[![Device lab](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml)
[![Release](https://img.shields.io/github/v/release/Mahdi-mortazavi/relay?sort=semver&color=4ADFBF&label=release)](https://github.com/Mahdi-mortazavi/relay/releases/latest)

</div>

<br>

---

<br>

Hi, I'm **Mahdi**. I built Relay because I kept ending up with a laptop that had
no internet and a phone that did, and every fix was worse than the problem —
USB tethering that needed drivers, hotspots that ate the battery, apps that
wanted an account for something that never has to leave my desk.

Relay shares your phone's connection with your PC over an encrypted **WireGuard**
tunnel, across your own Wi-Fi or the phone's hotspot. Every application on the PC
goes through it — **TCP and UDP** — so games, video calls and installers work,
not only the browser.

**No root. No account. No server. Nothing leaves your two devices.**

<br>

<div align="center">

<img src="docs/assets/android-sharing.png" alt="Relay on Android: one device connected, 4.2 GB up and 3.0 GB down, the two-digit pairing code, and a QR code" width="300">

<sub>The phone, sharing. The two digits are all the PC needs.</sub>

</div>

<br>

---

<br>

## Setup

<div align="center">

| 1 | 2 | 3 |
|:---:|:---:|:---:|
| **Phone** | **PC** | **Done** |
| Tap **Start Sharing** | Click the phone in the list<br><sub>or type the two digits</sub> | Approve it on the phone |

</div>

The PC finds phones that are already sharing, so there is usually nothing to type
at all. No camera? Type the two digits. No list? Scan the QR.

<div align="center">

<img src="docs/assets/windows-idle.png" alt="Relay on Windows: a compact window that lives in the system tray, offering Scan QR or Enter Code Manually" width="357">

<sub>The PC side is deliberately small — it lives in the tray and gets out of the way.</sub>

</div>

<br>

## Watch it work

**[KASRA MAX](https://github.com/Mahdi-mortazavi/relay/issues)** recorded a full
walkthrough — Persian narration with English subtitles, four minutes, from
install to browsing.

<div align="center">

<a href="https://raw.githubusercontent.com/Mahdi-mortazavi/relay/main/docs/assets/relay-demo-kasra.mp4"><img src="docs/assets/video-kasra-thumb.jpg" alt="Play the Relay walkthrough by KASRA MAX — Persian narration with English subtitles" width="720"></a>

<sub><b>▶︎ Play the walkthrough</b> — 4 minutes · Persian narration · English subtitles</sub>

</div>

> **Thank you, Kasra.** He also found three real bugs by using Relay properly and
> reporting exactly what he saw — including [the one where the approval prompt
> never appeared](https://github.com/Mahdi-mortazavi/relay/releases/tag/v2.7.1),
> which turned out to be two separate faults hiding behind each other. Reports
> like his are worth more than any test I can write, because they come from
> somewhere I was not looking.

<br>

## Download

<img src="docs/assets/icon.svg" alt="The Relay app icon" width="72" align="left" hspace="20" vspace="2">

One file per device, both from the same build that runs the tests on this page.
Nothing else to install, and no account to make.

<br clear="left">

| | File | Who it's for |
|---|---|---|
| **Windows** | [**Relay-Setup-x64.exe**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x64.exe) | Windows 10/11. Installs for you alone — no admin. |
| Windows 32-bit | [Relay-Setup-x86.exe](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-Setup-x86.exe) | Only if you know you need it. |
| **Android** | [**Relay-android-arm64-v8a.apk**](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-arm64-v8a.apk) | Android 8+. Almost every phone since 2017. |
| Android (any) | [Relay-android-universal.apk](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-universal.apk) | Bigger, works everywhere. Take this if the one above says *App not installed*. |
| Checksums | [SHA256SUMS.txt](https://github.com/Mahdi-mortazavi/relay/releases/latest/download/SHA256SUMS.txt) | `sha256sum -c SHA256SUMS.txt` |

> **Windows warns on first run** because the installer is not code-signed yet — **More info → Run anyway**.
> **Android says "App not installed"?** [Every reason I have seen is here](docs/install-troubleshooting.md).

<br>

## What Relay does

| | |
|---|---|
| **One WireGuard tunnel** | TCP *and* UDP, so games, calls and installers are shared — not only the browser |
| **Every application** | Nothing to configure per app; the whole machine goes through the phone |
| **No root** | On either device |
| **Honest state** | "Connected" means a real WireGuard handshake completed, not that an adapter exists |
| **Follows a moving phone** | A changed address — DHCP renewal, Wi-Fi change, NAT rebinding — re-points the tunnel instead of killing it |
| **Survives a bad network** | Tested against 5% packet loss, a full outage, and a mid-transfer path change |
| **Leak protection** | DNS and IPv6 cannot leave outside the tunnel. Costs 22 µs per connection and nothing per byte |
| **Updates itself** | Windows installs quietly at the next idle moment; Android offers, because the platform forbids more |

<details>
<summary><b>Pairing, in detail</b></summary>

<br>

| | |
|---|---|
| **One click** | Phones already sharing appear on the PC's first screen |
| **Two-digit code** | For a laptop with no camera |
| **QR code** | Point the webcam at the phone |
| **You approve it** | The phone asks before handing out keys, showing the requesting computer's address — in the app **and in the notification shade**, so it reaches you even when you are looking at the laptop |

</details>

<details>
<summary><b>Leak protection, in detail</b></summary>

<br>

On by default, using Windows Filtering Platform rules the tunnel installs for itself.

| Blocked | Left alone |
|---|---|
| DNS to any resolver but the tunnel's | `localhost` and loopback, v4 and v6 |
| IPv6 leaving the machine | Ordinary IPv4 |
| | **Other VPNs' traffic** — no port but 53 is touched |

Measured cost: **22 µs per new connection, and nothing per byte.** The rules live
in a session Windows tears down the instant the tunnel process ends — including
if it is killed. A dead Relay cannot leave your machine unable to resolve names.

</details>

<details>
<summary><b>On the phone</b></summary>

<br>

| | |
|---|---|
| **Quick Settings tile** | Start and stop from the shade, with the pairing code in the subtitle |
| **Home screen widget** | The code, big enough to read while you're looking at the laptop |
| **Long-press shortcut** | *Start Sharing* straight from the launcher icon |
| **First-run setup** | Walks through notifications, battery exemption, the tile and the widget — and adds them for you where Android allows it |
| **Three notification channels** | Sharing, updates and approval requests, each silenced independently |
| **Themed icon** | Follows your wallpaper palette on Android 13+ |

</details>

<details>
<summary><b>On the PC</b></summary>

<br>

| | |
|---|---|
| **Lives in the tray** | With minimise and close controls, and Alt-Tab as a way back |
| **Start with Windows** | A switch in Advanced, using the per-user key — no elevation |
| **Live statistics** | Speed, totals, tunnel latency and connection duration, read from the adapter |
| **Connect notification** | When the tunnel comes up — and a warning instead if it came up unprotected |
| **Diagnostic report** | One button, copies what a bug report needs — and nothing is uploaded |

</details>

<details>
<summary><b>Updates, and both languages</b></summary>

<br>

**Windows updates itself.** It checks shortly after launch and then daily, tells
you what it found, and installs at the next moment the tunnel is down — because
installing means stopping Relay, and doing that mid-call would drop the call. It
closes, updates, and comes back.

**Android checks when you open it and when sharing starts**, so the tile and the
widget reach you too, then offers the update. Android does not let a sideloaded
app install anything silently; the last tap is always yours.

Either way the download is verified against the **`SHA256SUMS.txt` published in
that same release**. Anything that fails is deleted, not kept.

English and Persian throughout, right-to-left correct, with every user-facing
string enforced by tests. Twenty-one error codes, each with a human explanation
in [`docs/errors.md`](docs/errors.md).

</details>

<br>

## Privacy

This is the part I care most about, so it is a rule rather than a preference:

- **No accounts, no servers, no telemetry, no analytics.** There is nothing to sign up for, and nowhere for your data to go.
- **The tunnel is WireGuard.** Its keys are minted per pairing and destroyed when sharing stops.
- **Keys are never broadcast.** The phone announces only enough to be *found*. The keys travel over a short exchange that you approve on screen, with the requesting computer's address shown.
- **Nothing outlives the process.** Relay changes no system-wide setting, so a crash cannot strand your machine in a broken state.

A change that adds a network call to anything but your own phone needs a very
good reason and a written decision record. That rule is enforced in
[`CLAUDE.md`](CLAUDE.md) and in the [ADRs](docs/adr/).

<br>

## How it's tested

Every commit installs the real app on real Android images (API 30–36) and drives
it through its own UI: start sharing, show a QR, let a laptop pair by code,
answer the prompt, stop. The Windows client runs against a **live phone** over
`adb`. The real installer is installed, launched and uninstalled. The tunnel is
brought up on a real WinTun adapter, and a real WireGuard handshake is verified
across it.

Then the *published* APK is installed on Android 11 through 16 — because a
release nobody can install is not a release.

**What hardware still has not proved is written down** in
[docs/testing.md](docs/testing.md) rather than hidden. A pipeline that skipped
its only meaningful test is also green, so the test names that matter are pinned
and CI fails if one silently skips.

<br>

## What's next

| | |
|:---|:---|
| **Signed Windows installer** | Next — it is the SmartScreen warning that stops people |
| **Split tunnelling / per-app routing** | Next — the tunnel is all-or-nothing today |
| **Sharing a phone that is itself on a VPN** | Detected and explained, not yet solved — [why](docs/vpn-compat.md) |
| **Linux client** | Wanted, and the best place to contribute — [#74](https://github.com/Mahdi-mortazavi/relay/issues/74) |
| **macOS client** | Later |
| **IPv6 inside the tunnel** | Later — the tunnel is IPv4 today |
| **More than one PC per session** | Not planned — one peer per session by design ([ADR-0009](docs/adr/0009-full-mode-only-and-code-pairing.md)) |
| **Accounts, servers, telemetry** | Never |

**Known limitation:** if the phone's own VPN captures Relay's UID, the tunnel's
traffic is swallowed by that VPN. Relay detects this and says so, but cannot fix
it from inside the app. [The detail is written down](docs/vpn-compat.md).

<sub>The full picture, with reasoning: [**ROADMAP.md**](ROADMAP.md)</sub>

<br>

## For developers

Two apps and one shared contract.

```
android/   Kotlin + Compose            windows/   .NET 8 + WinUI 3
wg/        WireGuard endpoint (Go)     shared/    the cross-platform contract
docs/      architecture, ADRs, testing, errors
```

**There is no local build step.** CI is the build system
([ADR-0004](docs/adr/0004-github-only-build-and-release.md)) — push a branch and
the pipeline builds it, tests it on real devices, and can cut a release. You do
**not** need Android Studio, the .NET SDK or Go installed to contribute.

```bash
git clone https://github.com/Mahdi-mortazavi/relay.git
cd relay
git switch -c my-change
# edit, commit, push — CI builds and tests it for you
```

Three things that are easy to break by accident, worth knowing before your first PR:

1. **Change `/shared` first.** The wire format, the state machine and the pairing rules live there, and both platforms are asserted against it. Editing one platform to match the other is how the two apps drift.
2. **Every user-facing string exists in English *and* Persian.** This is enforced by tests, not by review.
3. **Green is not the same as tested.** If you add a test that matters, add its name to the guard, so a run that silently skipped it cannot pass.

Start with [`docs/architecture.md`](docs/architecture.md) and the
[ADRs](docs/adr/) — every significant decision is written down together with the
reasoning that produced it. [`CONTRIBUTING.md`](CONTRIBUTING.md) has the rest,
and issues that are a good place to start are labelled **`good first issue`**.

<br>

---

<br>

<div align="center">

<img src="https://avatars.githubusercontent.com/u/127998145?v=4" width="104" alt="Mahdi Mortazavi">

### Mahdi Mortazavi

<sub>Full-stack developer · product builder · Iran</sub>

<br>

I don't build demos — I build the thing I needed, then keep working on it until
it is good enough to hand to someone else.

Relay is exactly that. It began as my own frustration with a laptop that had no
internet and a phone that did. Now it runs a device lab on every commit, ships on
two platforms, and tells you the truth about what it cannot do yet. **That last
part is the standard I hold my work to** — I would rather write down a limitation
than let you discover it.

If Relay is useful to you, a star genuinely helps. If it breaks,
[tell me](https://github.com/Mahdi-mortazavi/relay/issues/new/choose) — I read
everything.

<br>

[![Telegram](https://img.shields.io/badge/Telegram-@Mahdi__mortazavi1-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/Mahdi_mortazavi1)
&nbsp;
[![Community](https://img.shields.io/badge/Community-Startup_Legend-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/Startup_legend)
&nbsp;
[![Email](https://img.shields.io/badge/Email-Get_in_touch-EA4335?style=flat-square&logo=gmail&logoColor=white)](mailto:Mahdi.mortazavi.135@gmail.com)
&nbsp;
[![Website](https://img.shields.io/badge/Website-mahdi--mortazavi.github.io-4ADFBF?style=flat-square&logo=googlechrome&logoColor=white)](https://mahdi-mortazavi.github.io)

<br>

<sub>Made in Iran · [GPL-3.0](LICENSE) · "WireGuard" is a registered trademark of Jason A. Donenfeld.</sub>

</div>
