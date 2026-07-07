# ADR-0005: Windows stack — .NET 8 + WinUI 3, unpackaged, EXE installer

**Status:** Accepted · **Date:** 2026-07-07

## Context

The spec prefers .NET 8 + WinUI 3 for native acrylic/mica materials, with WPF as fallback and Tauri as a possible alternative for a future macOS port. The app must build headlessly on `windows-latest` runners and ship x64 + x86 installers without a code-signing certificate.

## Decision

1. **WinUI 3 (Windows App SDK) on .NET 8**, as preferred by the spec. Native mica/acrylic gives the Liquid Glass material honestly instead of imitating it.
2. **Tray icon via `H.NotifyIcon.WinUI`** (maintained library). WinUI 3 has no first-class tray API; this is the standard interop layer. If it proves unworkable in practice, the documented fallback is WPF — re-evaluated only on real evidence.
3. **Tauri rejected:** the macOS-port benefit does not outweigh losing native materials and the mature .NET story for registry/WinINet proxy work and WireGuard/WinTun integration. The macOS client (roadmap) will be a native menu-bar app.
4. **Unpackaged deployment (`WindowsPackageType=None`) + EXE installer (Inno Setup), not MSIX.** Unsigned MSIX packages are effectively uninstallable for normal users (Windows blocks them; self-signed certificates require manual trust — worse than SmartScreen). An unsigned EXE installer shows a bypassable SmartScreen warning, which is the lesser trade-off and is documented in `docs/release.md`. Revisit if a code-signing certificate is obtained.

## Consequences

- CI builds with the `dotnet` CLI on `windows-latest` for both `x64` and `x86` platforms.
- Self-contained publishing bundles the Windows App SDK runtime so users install nothing else.
- SmartScreen warning on first run is a known, documented trade-off, not a bug.
