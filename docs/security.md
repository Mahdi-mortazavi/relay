# Security

## Current model (MVP, payload v1)

Pairing is intentionally simple: the QR (or typed code) carries connection parameters directly — it is a **bearer credential** while sharing is active. Mitigating factors: the hotspot is WPA2/WPA3-protected, the QR requires physical proximity to the phone screen, sharing is user-initiated and visible (persistent notification), and everything is local-only (zero telemetry, no cloud).

WireGuard keys in Full Mode are generated **per pairing** and discarded on disconnect — nothing long-lived to steal.

## Upgrade path (designed now, implemented later)

Pairing sits behind a `PairingStrategy` interface and the payload is versioned (`v`), so stronger schemes slot in without touching transport code:

1. **Expiring QR** — `issuedAt` (already in v1) + a TTL; stale codes rejected.
2. **One-time connection token** — the payload carries a nonce the server accepts exactly once.
3. **Mutual device confirmation** — both screens show a short fingerprint; the user confirms on the phone before traffic flows.

Each step bumps `v`; old clients get the standard "made by a newer version of Relay — please update" rejection.

## Reporting

Please report vulnerabilities privately via GitHub Security Advisories ("Report a vulnerability" on the repo) rather than public issues.
