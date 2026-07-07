# ADR-0001: Two transport modes — SOCKS5 (Fast) and WireGuard (Full)

**Status:** Accepted · **Date:** 2026-07-07

## Context

Relay must cover two very different traffic profiles: everyday TCP browsing/streaming, and latency-sensitive UDP workloads (games, VoIP, video calls) that plain proxies cannot carry (problem P3). A single transport either over-engineers the common case or under-serves the UDP case.

## Decision

Ship two user-selectable modes behind one toggle:

- **Fast Mode — SOCKS5, default.** A local TCP proxy on the phone's hotspot interface. Minimal moving parts, instant setup, no tunnel driver on Windows.
- **Full Mode — WireGuard.** The phone runs a WireGuard endpoint (official `com.wireguard.android`); Windows uses the official WinTun-based stack. Full TCP+UDP routing through the phone's VPN.

The QR payload's `mode` field (versioned schema in `/shared`) tells the client which path to configure, so the pairing flow is identical for both.

## Consequences

- Fast Mode ships in Phase 1; Full Mode in Phase 3 — the schema already reserves the `wg` block so no wire-format break is needed.
- Two system-integration paths on Windows (proxy registry vs. tunnel adapter) must both implement the transactional snapshot/rollback invariant.
- No custom crypto or hand-rolled tunnels ever: WireGuard components are the official implementations only.
