# Backlog

Ideas and known follow-ups that are **not** in the current phase. Nothing here may be built early (no scope creep) — items graduate into a phase deliberately.

- **Authenticate the pairing, so Relay is not an open proxy on a shared network**
  (see `SECURITY.md` → threat model). Windows' system proxy has no way to supply
  SOCKS credentials, so this cannot be RFC 1929 auth; the shape that fits is
  admitting the first client that the user confirms on the phone and asking
  about every device after it, behind the existing `PairingStrategy` seam.
- Full Mode: the gomobile `wireguard-go` AAR has no build step, so
  `WgForwarderProvider` never resolves `GoWgForwarder` and the mode is hidden.
  Landing it needs the AAR build in CI *and* a `-keep` rule, since the class is
  only referenced reflectively and R8 would strip it from the release APK.
- Harden `--restore-proxy`: it currently boots the whole WinUI stack (which the
  code itself documents as throwing stowed COM exceptions when unpackaged) to
  run three lines of registry code. A custom entry point that handles the flag
  before any XAML initialisation would make the uninstall-time rollback far
  harder to break.
- `windows/Relay.App/Strings/*/Resources.resw` are unreferenced at runtime and
  have already drifted from `Strings.cs`. Removing them risks the fragile
  `resources.pri` generation, so it needs a build to verify rather than a blind
  delete.
- x64 and x86 installers share one `AppId` and install directory, so running the
  wrong one over an existing install is treated as an upgrade. Fixing it means
  changing `AppId`, which orphans existing installs — needs a migration plan.
- ~~**Toolchain upgrade: compileSdk 37 + a newer AGP + Kotlin 2.4.**~~ **Done.**
  It landed as AGP 9.3.1 / Gradle 9.7.0 / Kotlin 2.4.10 / compileSdk 37. Two
  things this entry had guessed at turned out to be sharper than expected:
  the blocker is not compileSdk at all — `androidx.core 1.19.0` and
  `lifecycle 2.11.0` demand *AGP 9.1.0 or higher* by name, so the last 8.x
  (8.13.2) cannot satisfy them; and AGP 9 folded Kotlin support into itself,
  making `org.jetbrains.kotlin.android` a hard error rather than a warning.
  CI stayed on JDK 17, which was confirmed rather than assumed by reading the
  AGP 9.3.1 jar's class-file version (61 = Java 17).
- Windows code-signing certificate → signed installers, no SmartScreen warning (see `docs/release.md`).
- OEM-specific battery-manager guidance (Xiaomi/MIUI, Samsung, Huawei aggressive killers) beyond the stock exemption flow.
- Bytes-transferred counter in the Android status view (Phase 1 marks it nice-to-have).
- Solution (`.sln`) file for the Windows projects for IDE convenience (CI builds per-csproj and doesn't need it).
- Publish the arm64-v8a APK alongside a universal APK if non-ARM devices ever matter.
- In-app update check against GitHub Releases (must stay opt-in / privacy-respecting).
- Widen the typed code beyond `192.168.0.0/16` (issue #18). A shared LAN on `10.x` or
  `172.16–31.x` gets QR-only pairing today. 8 characters cannot hold it — 10.x alone needs
  24 host + 16 port bits, leaving nothing for the checksum — so this means a longer code for
  the wider ranges (e.g. 10 chars = 50 bits: 3-bit range selector + host + port + CRC), with
  the existing 8-char form kept as-is for `192.168.x.x`. Touches `shared/typed-code.md`,
  both platforms, and `shared/test-vectors.json` together.
