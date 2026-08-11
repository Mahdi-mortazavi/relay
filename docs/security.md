# Security

## Current model (MVP, payload v1)

Pairing is intentionally simple: the QR (or typed code) carries connection parameters directly — it is a **bearer credential** while sharing is active. Mitigating factors: the hotspot is WPA2/WPA3-protected, the QR requires physical proximity to the phone screen, sharing is user-initiated and visible (persistent notification), and everything is local-only (zero telemetry, no cloud).

**What actually keeps strangers out is the approval prompt, not the code.** The
first connection from a computer nobody has approved is held while the phone
asks its owner, showing the address (`ClientGate`, contract in
[`/shared/pairing-beacon.md`](../shared/pairing-beacon.md)). Sixty seconds of
silence counts as a refusal, so a phone in a pocket fails closed, and decisions
last one sharing session and are never written down — a decision remembered from
a network you have since left is a decision about the wrong network.

This matters most for the two-digit pairing code. Ninety values could never be a
secret, and it is not treated as one: it selects which phone on the network to
talk to, and the human consents. Nothing is weakened relative to the
eight-character code, which was never a secret either — it was an address in
disguise, and anything on the network could already find the port by scanning
for it. What the beacon changes is that finding it no longer needs a scan, which
is exactly why the prompt exists.

WireGuard keys in Full Mode are generated **per pairing** and discarded on disconnect — nothing long-lived to steal.

## Upgrade path (designed now, implemented later)

Pairing sits behind a `PairingStrategy` interface and the payload is versioned (`v`), so stronger schemes slot in without touching transport code:

1. **Expiring QR** — `issuedAt` (already in v1) + a TTL; stale codes rejected.
2. **One-time connection token** — the payload carries a nonce the server accepts exactly once.
3. ~~**Mutual device confirmation**~~ — **shipped**, in the form described
   above: the phone confirms before traffic flows. What remains of this step is
   the fingerprint on both screens, so the person can check that the computer
   asking is the one in front of them rather than another on the same network.

Each step bumps `v`; old clients get the standard "made by a newer version of Relay — please update" rejection.

## Reporting

Please report vulnerabilities privately via GitHub Security Advisories ("Report a vulnerability" on the repo) rather than public issues.
