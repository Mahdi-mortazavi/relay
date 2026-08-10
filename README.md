<div align="center">

# Relay

**Share your phone's connection with your PC. Scan a QR code. That's the setup.**

[![CI](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml)
[![E2E](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml)
[![Security](https://github.com/Mahdi-mortazavi/relay/actions/workflows/security.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/security.yml)
[![Latest release](https://img.shields.io/github/v/release/Mahdi-mortazavi/relay?sort=semver)](https://github.com/Mahdi-mortazavi/relay/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

[English](README.md) · [فارسی](README.fa.md)

<p>
<img src="docs/assets/android-idle.png" alt="The Relay app on Android, ready to share" width="230">
&nbsp;&nbsp;
<img src="docs/assets/windows-idle.png" alt="The Relay tray app on Windows, waiting to pair" width="230">
</p>

</div>

## The problem

I kept hitting the same wall. My phone had a working connection — sometimes through a VPN — and my laptop did not. Sharing it should have been trivial. It never was.

Turning on the hotspot passed the phone's *raw* connection through, not the VPN. The tools that could do better wanted an IP address, a port number, and a trip into Windows proxy settings. Then the phone's screen would turn off and the whole thing would quietly die. And when I was done, the proxy I had set by hand stayed set by hand — usually discovered later, when nothing on the laptop could reach the internet any more.

Every one of those is a small problem. Together they meant I just didn't bother.

## What Relay does

Relay is a small pair of apps — one on Android, one on Windows — that connect to each other by QR code and route the PC's traffic through the phone.

1. **Tap Start Sharing** on the phone. A QR code appears.
2. **Scan it** with the Windows tray app. (Or type the short code shown next to it.)
3. **Done.** The laptop is online through the phone.

No account. No cloud service. No IP addresses, no port numbers, and you never open network settings on either device. Because the phone opens the outbound sockets, whatever VPN is active on the phone carries the laptop's traffic too — including DNS.

When you disconnect, Relay puts your Windows proxy settings back exactly as it found them, and checks that it worked.

> **Local-only. Zero telemetry.** The apps make no network connections other than relaying your own traffic between your two devices. No analytics, no accounts, no crash reporting, no update pings.

## How it works

```
┌──────────────────────────┐                    ┌──────────────────────────┐
│  Android                 │   hotspot or the   │  Windows                 │
│                          │   same Wi-Fi       │                          │
│  Foreground service      │◄──────────────────►│  Tray app                │
│  └─ SOCKS5 proxy         │                    │  └─ System proxy, set    │
│                          │                    │     and restored for you │
│  Shows the QR            │                    │  Scans the QR            │
└────────────┬─────────────┘                    └──────────────────────────┘
             │
   the phone's VPN, if any → the internet
```

The QR carries a small versioned payload: an address, a port, and which transport to use. There is no discovery protocol and no pairing server, which is exactly why it still works when a VPN is up — system VPNs on Android 10+ break local network discovery, so Relay simply never discovers anything.

Both devices need to be on the same network: the phone's own hotspot, or a Wi-Fi network they are both joined to.

Read more in [`docs/architecture.md`](docs/architecture.md).

## Install

Grab the latest build from the [**Releases page**](https://github.com/Mahdi-mortazavi/relay/releases). There is nothing to compile.

| | File | Notes |
|---|---|---|
| **Windows** | `Relay-Setup-x64-<version>.exe` | Windows 10 or 11. Pick x86 only if you know you need it. Installs per-user, no administrator prompt. |
| **Android** | `relay-arm64-v8a-<version>.apk` | Android 8.0 or newer. Enable "install from unknown sources" for your browser or file manager. |

The Windows installer is not code-signed yet, so SmartScreen will show a warning on first run — **More info → Run anyway**. Checksums are published with every release; [`docs/release.md`](docs/release.md) tracks the signing work.

## Use it

1. On the phone, turn on the hotspot and connect the PC to it — or make sure both are on the same Wi-Fi.
2. Open Relay on the phone and tap **Start Sharing**.
3. Open Relay on the PC (it lives in the tray) and click **Scan QR**, then hold the phone up to the webcam. No webcam? Click **Enter Code Manually** and type the 8-character code under the QR.
4. The PC says **Connected**. Use it normally.
5. Click **Disconnect** on the PC or **Stop** on the phone when you're finished.

The phone keeps sharing with the screen off. On the first run it will offer to exempt itself from battery optimisation — accept, or Android will eventually kill the session.

**Today Relay carries TCP**, which covers browsing, streaming, downloads and most apps. UDP — games, some video calls — needs the WireGuard transport, which is [on the roadmap](docs/roadmap.md) and not in this build. The app does not offer it, rather than offering it and failing.

## Is it secure?

Short version: **fine on your own hotspot, not private on a café network.** Relay's transport is a SOCKS5 proxy with no authentication, because Windows' system proxy has no way to supply credentials — so anyone else on the same network who finds the port can relay traffic through your phone.

That is a real limitation and it deserves a straight answer rather than a footnote: [**SECURITY.md**](SECURITY.md) has the full threat model, what Relay does guarantee, and how to report a vulnerability privately.

## How it's tested

Relay changes your operating system's network settings, so "it worked on my machine" isn't good enough. Every pull request runs a lab that GitHub builds from scratch:

- the **real APK on a real Android emulator** (API 30 and 34), driven through the real UI, relaying real HTTP traffic through the real SOCKS5 server;
- the **Windows client's own code against a live phone** over `adb`, so the shipping decoder reads the phone's actual QR payload and real bytes cross between the two platforms;
- the **real Windows installer** — install, launch, restore, uninstall — with the proxy registry read back and compared after every single stage.

What the lab *can't* reach (a physical camera scanning a screen, WinUI control automation, Windows sleep) is written down as blocked in [`docs/testing.md`](docs/testing.md) instead of quietly skipped.

## For developers

```
android/   Android app (Kotlin + Jetpack Compose)
windows/   Windows tray app (.NET 8 + WinUI 3) + shared Relay.Core
shared/    Contracts both apps consume: QR schema, test vectors, state machine, design tokens
docs/      Architecture, ADRs, testing, security, release process
```

Anything under `shared/` is the single source of truth. Change it **first**, then both platforms — the unit tests on each side assert against those files, so the two implementations cannot drift.

**Android** — JDK 17 and an Android SDK with platform 35:

```bash
cd android
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests, including the SOCKS5 protocol suite
```

**Windows core** — .NET 8, runs on any OS:

```bash
dotnet test windows/Relay.App.Tests/Relay.App.Tests.csproj
```

**The Windows app itself** needs Windows and Visual Studio's MSBuild (the WinUI 3 PRI packaging tasks are not in the dotnet CLI's MSBuild):

```powershell
msbuild windows/Relay.App/Relay.App.csproj /restore /p:Configuration=Release /p:Platform=x64
```

The device lab needs an emulator and is CI-only; see [`docs/testing.md`](docs/testing.md) for what it runs and why.

[`CONTRIBUTING.md`](CONTRIBUTING.md) covers the workflow, and [`docs/adr/`](docs/adr/) records why the architecture is the way it is — start at ADR-0001.

## Roadmap

Honest status, not a wish list — see [`docs/roadmap.md`](docs/roadmap.md) for detail.

| | Status |
|---|---|
| Fast Mode (SOCKS5, TCP) | **Shipping** |
| Auto-reconnect, actionable errors, EN + FA | **Shipping** |
| Full Mode (WireGuard, TCP + UDP) | Planned |
| Authenticated pairing | Planned — see [SECURITY.md](SECURITY.md) |
| Signed Windows installer | Planned |
| macOS client | Later |

## License

[Apache-2.0](LICENSE). "WireGuard" is a registered trademark of Jason A. Donenfeld.

Built by [Mahdi Mortazavi](https://github.com/Mahdi-mortazavi).
