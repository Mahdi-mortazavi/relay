# ADR-0002: No local discovery — direct connect via versioned QR payload

**Status:** Accepted · **Date:** 2026-07-07

## Context

Android 10+ system VPNs commonly break local network discovery (mDNS/Bonjour), which is the root cause of "client can't find the server" failures in existing tools (problem P2). Manual IP:port entry is the other extreme and the root cause of problem P4.

## Decision

There is **no discovery protocol at all**. The phone renders a QR code containing a versioned JSON payload (base64url) with everything the client needs — mode, hotspot IP, port, and WireGuard parameters in Full Mode. A short typed code (base32, no `O/0/I/1`) is the camera-less fallback. The schema and canonical test vectors live in `/shared` and both apps validate against them.

Pairing sits behind a `PairingStrategy` interface with the payload version `v` as the negotiation point, so stronger schemes (expiring QR, one-time tokens, mutual confirmation) can be added later without touching transport code (see `docs/security.md`).

## Consequences

- Zero dependence on multicast/mDNS → immune to VPN interference by construction.
- The QR is a bearer credential in v1 (anyone who scans it while sharing is active can connect). Acceptable for the MVP threat model (personal hotspot, physical proximity, WPA2-protected network); the upgrade path is designed in from day one.
- Clients must hard-reject unknown `v` values with an actionable "update Relay" message.
