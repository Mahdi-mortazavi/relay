<div align="center">

# Relay

**Share your connection. Instantly.**

[![CI](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/ci.yml)
[![E2E](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml/badge.svg)](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml)
[![Latest release](https://img.shields.io/github/v/release/Mahdi-mortazavi/relay?sort=semver)](https://github.com/Mahdi-mortazavi/relay/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

</div>

Relay is an open-source, privacy-first utility that shares one personal device's internet connection with another over a Wi-Fi hotspot — with **zero manual network configuration**. Turn on sharing on your Android phone, scan a QR code on your Windows laptop, and the laptop's traffic flows through the phone's connection, including whatever VPN is active on the phone.

> **Local-only. Zero telemetry.** No traffic, IPs, logs, or any other data ever leave your two devices. There are no analytics, no accounts, and no cloud services of any kind.

## How it works

1. **Start sharing** on the phone — one button. A foreground service keeps the connection alive, even with the screen off.
2. **Scan the QR** with the Windows tray app (or type the short code shown next to it).
3. **Done.** Windows configures itself automatically and restores everything exactly as it was when you disconnect. You never open network settings on either device.

### Two modes

| Mode | Transport | Best for |
|---|---|---|
| **Fast** (default) | SOCKS5 (TCP) | Browsing and streaming |
| **Full** | WireGuard® (TCP + UDP) | Games and video calls |

## Install

**Download from the [Releases page](https://github.com/Mahdi-mortazavi/relay/releases).** There is nothing to build — every release ships installable artifacts:

- **Android:** `relay-arm64-v8a-<version>.apk` — enable "install from unknown sources" and install. (An `.aab` is also attached for store distribution.)
- **Windows:** `Relay-Setup-x64-<version>.exe` or `Relay-Setup-x86-<version>.exe` — pick the one matching your system (x64 for almost all modern PCs).

Screenshots are coming with the first feature release. In the meantime, every
[E2E run](https://github.com/Mahdi-mortazavi/relay/actions/workflows/e2e.yml)
uploads real screenshots of each state, captured on an emulator during the
automated journey.

## How it is tested

Relay changes your operating system's network settings, so "it worked on my
machine" is not good enough. Every pull request runs a test lab that GitHub
provisions from scratch:

- the **real APK** on a **real Android emulator** (API 30 and 34), driven
  through the real UI — start sharing, show a QR, accept a client, relay real
  HTTP traffic, then verify that **Stop** tears down the live tunnel too;
- the **Windows client's own code against a live phone** over `adb`, so the
  shipping decoder reads the phone's actual QR payload and real bytes cross
  between the two platforms;
- the **real Windows installer** — install, launch, restore, uninstall — with
  the system proxy registry read back and compared after every stage.

What the lab *cannot* reach (a physical camera scanning a screen, WinUI control
automation, Windows sleep) is listed as blocked in
[`docs/testing.md`](docs/testing.md) rather than quietly skipped.

## Security and privacy

Relay makes no network connections other than relaying your own traffic, keeps
no accounts and collects nothing. Its transport is an unauthenticated SOCKS5
proxy, which is safe on your own hotspot and **is an open proxy on a shared
café or office network** — [`SECURITY.md`](SECURITY.md) explains exactly what
that means, why it is currently a constraint of Windows' proxy support, and how
to report a vulnerability privately.

## Design

Both apps share one design language — translucent, depth-layered "liquid glass" surfaces, a near-monochrome palette with a single accent, and fluid spring-based motion — driven by a common token set in [`/shared/design-tokens.json`](shared/design-tokens.json). English and Persian (fully right-to-left) are supported from the first release.

## Project layout

```
android/   Android app (Kotlin + Jetpack Compose)
windows/   Windows tray app (.NET 8 + WinUI 3)
shared/    Contracts both apps consume: QR payload schema, test vectors, state machine, design tokens
docs/      Architecture, ADRs, design references, testing, release process
```

Everything is built, tested, and released exclusively in GitHub Actions — see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache-2.0](LICENSE). "WireGuard" is a registered trademark of Jason A. Donenfeld.
