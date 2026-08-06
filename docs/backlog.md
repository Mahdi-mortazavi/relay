# Backlog

Ideas and known follow-ups that are **not** in the current phase. Nothing here may be built early (no scope creep) — items graduate into a phase deliberately.

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
