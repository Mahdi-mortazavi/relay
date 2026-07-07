# Backlog

Ideas and known follow-ups that are **not** in the current phase. Nothing here may be built early (no scope creep) — items graduate into a phase deliberately.

- Windows code-signing certificate → signed installers, no SmartScreen warning (see `docs/release.md`).
- OEM-specific battery-manager guidance (Xiaomi/MIUI, Samsung, Huawei aggressive killers) beyond the stock exemption flow.
- Bytes-transferred counter in the Android status view (Phase 1 marks it nice-to-have).
- Solution (`.sln`) file for the Windows projects for IDE convenience (CI builds per-csproj and doesn't need it).
- Publish the arm64-v8a APK alongside a universal APK if non-ARM devices ever matter.
- In-app update check against GitHub Releases (must stay opt-in / privacy-respecting).
