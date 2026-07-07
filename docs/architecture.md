# Relay — Architecture

## Overview

Relay shares an Android phone's internet connection (including any VPN active on the phone) with a Windows PC over the phone's Wi-Fi hotspot, with zero manual network configuration on either device.

```
┌─────────────────────────────┐          ┌──────────────────────────────┐
│  Android (source/server)    │          │  Windows (client)            │
│                             │  hotspot │                              │
│  Foreground service         │◄────────►│  Tray app                    │
│  ├─ SOCKS5 proxy (Fast)     │  Wi-Fi   │  ├─ System proxy (Fast)      │
│  └─ WireGuard peer (Full)   │          │  └─ WinTun tunnel (Full)     │
│  QR/typed-code issuer       │          │  QR scanner / code entry     │
└──────────────┬──────────────┘          └──────────────────────────────┘
               │
       Phone's active VPN → Internet
```

## The four problems Relay fixes by design

| # | Problem in existing tools | Relay's answer |
|---|---|---|
| P1 | Sharing dies after screen-off / battery saver | True foreground service + guided battery-exemption onboarding + partial WakeLock only during active transfer |
| P2 | System VPN (Android 10+) breaks local discovery | **No discovery at all** — the client connects directly to the hotspot IP:port carried in the QR payload |
| P3 | HTTP-only proxies can't pass UDP | Full Mode is real WireGuard: TCP + UDP both tunnel |
| P4 | Manual IP/port typing in OS settings | QR-driven auto-config; all system changes are transactional with verified rollback |

## Transport modes

- **Fast Mode (SOCKS5)** — default. The phone runs a local SOCKS5 proxy bound to the hotspot interface; sockets it opens are routed through the phone's active VPN by Android itself. Windows sets the system proxy automatically. TCP only.
- **Full Mode (WireGuard)** — the phone runs a WireGuard endpoint on the hotspot interface (official `com.wireguard.android`); Windows brings up a WinTun-based tunnel (official WireGuard stack). TCP + UDP. Per-pairing keys are generated fresh and discarded on disconnect.

One toggle switches modes; the QR payload's `mode` field tells the client what to do.

## Pairing

The QR encodes a **versioned JSON payload** (base64url) defined by [`/shared/qr-payload.schema.json`](../shared/qr-payload.schema.json). A short typed code is the fallback for laptops without a camera. There is no discovery protocol and no pairing server. Pairing logic sits behind a `PairingStrategy` interface so stronger schemes (expiring QR, one-time token, mutual confirmation — see [`security.md`](security.md)) can be added later by bumping `v` without touching transport code.

## Shared contracts

`/shared` is the single source of truth for the QR schema + test vectors, the connection state machine ([`connection-states.json`](../shared/connection-states.json)), and the design tokens. Both apps' unit tests consume the same vectors so platforms cannot drift.

## Connection state machine

Both apps model: `Idle → Preparing → Advertising(QR) → Connected(n) → Error(reason)` with the transitions pinned in `/shared/connection-states.json`. UI is a pure function of this state; there are no ad-hoc booleans.

## Safety invariants (non-negotiable)

1. **Transactional system changes.** Windows snapshots proxy/adapter state before touching it and restores on disconnect *and* on crash (cleanup handler); correctness is asserted by reading state back. Android mirrors the same discipline for its service state.
2. **Local-only, zero telemetry.** The apps make no network connections other than relaying the user's own traffic.
3. **Neutral framing.** Relay is a general-purpose connection-sharing utility (see CONTRIBUTING.md).

## Repository layout

See the tree in [README.md](../README.md). CI (`.github/workflows/ci.yml`) is the only build system — the project must always build headlessly on stock GitHub runners.

## Decision log

Architecture Decision Records live in [`docs/adr/`](adr/). Start with ADR-0001.
