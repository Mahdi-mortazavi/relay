# Relay — Architecture

## Overview

Relay shares an Android phone's internet connection — including any VPN active
on the phone — with a Windows PC, over whatever already connects the two: the
same Wi-Fi, the phone's hotspot, or a USB cable. Nothing on either device is
configured by hand, and nothing runs anywhere but on those two devices.

```
┌──────────────────────────────────┐            ┌───────────────────────────────┐
│  Android (the endpoint)          │  Wi-Fi /   │  Windows (the client)         │
│                                  │  hotspot / │                               │
│  Foreground service              │  USB       │  Tray app (WinUI 3)           │
│  ├─ WireGuard endpoint (Go:      │◄──────────►│  ├─ relaywg-client (Go)       │
│  │  wireguard-go + gVisor        │  UDP,      │  │  WinTun adapter, whole     │
│  │  netstack, gomobile AAR)      │  encrypted │  │  system routed through it  │
│  ├─ Pairing beacon + approval    │            │  ├─ Phone list / 2-digit code │
│  └─ Route choice (ADR-0011)      │            │  │  / QR                      │
└───────────────┬──────────────────┘            │  └─ Optional kill switch      │
                │                               └───────────────────────────────┘
   phone's own route  ─or─  the VPN app's local proxy
                │
             Internet
```

## The problems Relay fixes by design

| # | Problem in existing tools | Relay's answer |
|---|---|---|
| P1 | Sharing dies after screen-off / battery saver | True foreground service + guided battery-exemption onboarding |
| P2 | Tethering and hotspots usually bypass the phone's VPN | Traffic leaves from Relay's own sockets, so it takes the phone's route — inside the VPN, or through the VPN app's local proxy ([ADR-0010](adr/0010-forward-through-a-local-proxy.md), [ADR-0011](adr/0011-automatic-route.md)) |
| P3 | Proxy apps can't pass UDP and only cover apps that honour them | One WireGuard tunnel: TCP and UDP, every app ([ADR-0009](adr/0009-full-mode-only-and-code-pairing.md)) |
| P4 | Manual IP/port typing in OS settings | The PC finds the phone; a two-digit code or a QR picks it ([`pairing-beacon.md`](../shared/pairing-beacon.md)) |

## Transport

One transport, WireGuard ([ADR-0009](adr/0009-full-mode-only-and-code-pairing.md)
retired the SOCKS5 "Fast Mode"). The phone is the server and is unrooted, so it
cannot NAT a kernel interface: it runs `wireguard-go` over a gVisor netstack and
opens ordinary sockets for each of the PC's flows ([ADR-0008](adr/0008-full-mode-wireguard-forwarder.md)).
The tunnel is `10.13.37.0/24` — phone `.1`, PC `.2`. "Connected" comes from a
real handshake, never from a socket merely being open.

**Route.** While sharing, the phone re-decides every ten seconds where the PC's
traffic leaves — its own route, or a VPN app's local proxy — and swaps the
endpoint's upstream without dropping the tunnel ([ADR-0011](adr/0011-automatic-route.md)).

**Windows.** `relaywg-client` brings up a WinTun adapter that exists only while
its process does, so there is no system setting to restore after a crash. DNS
and IPv6 are kept from leaking around it; the optional kill switch holds the
route when the phone stops answering.

## Pairing

The phone announces itself on the local network and shows a two-digit code; the
PC lists the phones it hears, or takes the code, or scans the QR, whose payload
is defined by [`/shared/qr-payload.schema.json`](../shared/qr-payload.schema.json).
The code is a selector, not a secret: a computer pairing by code gets nothing
until the person holding the phone taps **Allow** on a prompt that names it.
Keys are made per pairing and destroyed when sharing stops.

## Shared contracts

`/shared` is the single source of truth for the QR schema + test vectors, the connection state machine ([`connection-states.json`](../shared/connection-states.json)), and the design tokens. Both apps' unit tests consume the same vectors so platforms cannot drift.

## Connection state machine

Both apps model: `Idle → Preparing → Advertising → Connected → Error` with the transitions pinned in `/shared/connection-states.json`. UI is a pure function of this state; there are no ad-hoc booleans.

## Safety invariants (non-negotiable)

1. **Nothing outlives the app.** Relay changes no system-wide setting: the Windows tunnel adapter vanishes with its process, so a crash cannot strand the PC. Android mirrors the same discipline for its service state.
2. **Local-only, zero telemetry.** The apps make no network connections other than relaying the user's own traffic.
3. **Neutral framing.** Relay is a general-purpose connection-sharing utility (see CONTRIBUTING.md).

## Repository layout

See the tree in [README.md](../README.md). CI (`.github/workflows/ci.yml`) is the only build system — the project must always build headlessly on stock GitHub runners.

## Decision log

Architecture Decision Records live in [`docs/adr/`](adr/). Start with ADR-0001.
