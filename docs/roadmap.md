# Roadmap

## Phases (strict order, one PR each, hard approval gate between)

| Phase | Scope | Status |
|---|---|---|
| 0 | Scaffolding: monorepo, docs, ADRs, green headless CI for both skeletons | **In progress** |
| 1 | MVP — Fast Mode (SOCKS5): Android foreground service + QR issuing; Windows tray client + auto proxy with verified rollback; EN+FA; Liquid Glass | Pending |
| 2 | Hardening & UX polish: actionable errors, auto-reconnect, Advanced settings, VPN compatibility matrix, motion polish, dark/light | Pending |
| 3 | Full Mode (WireGuard): TCP+UDP tunneling, per-pairing keys, mode toggle | Pending |
| 4 | Documentation of the longer-term roadmap below (docs only) | Pending |

## Beyond (Phase 4 documents these; implementation only on explicit go-ahead)

- **macOS** menu-bar client mirroring the Windows app.
- **iOS** via exporting a standard WireGuard config into the official WireGuard app.
- **Security upgrade path** via `PairingStrategy`: expiring QR → one-time connection token → mutual device confirmation (see [`security.md`](security.md)).
