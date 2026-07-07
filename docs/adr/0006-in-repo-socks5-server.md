# ADR-0006: In-repo SOCKS5 server on Android

**Status:** Accepted · **Date:** 2026-07-07

## Context

Fast Mode needs a SOCKS5 proxy on the phone that forwards TCP through whatever
network is active (including the phone's VPN). §5.1 calls for a "small,
well-reviewed impl (state choice + why); memory-safe, backpressure-aware." The
candidate Android/JVM SOCKS libraries are either heavyweight full-proxy
frameworks, unmaintained, or bring HTTP/other-protocol surface we don't want in
a tool whose whole value proposition is being auditable and local-only.

## Decision

Implement a **minimal SOCKS5 server in-repo** (`net/Socks5Server.kt`) rather than
adding a dependency:

- **CONNECT command only**, no authentication, TCP only. UDP is out of scope for
  Fast Mode by design (it rides WireGuard in Full Mode, Phase 3).
- Outbound sockets are ordinary app sockets, so Android's routing sends them —
  and DNS for domain requests, resolved on the phone — through the active VPN.
  This is the mechanism that makes Relay share the VPN, and it falls out of using
  plain sockets rather than a framework that manages its own transport.
- **Backpressure by construction:** each direction is a blocking copy loop on the
  IO dispatcher with a bounded buffer, so a slow reader throttles its writer. No
  unbounded queues.
- The whole server is a few hundred lines a reviewer can read in one sitting —
  which matters more here than feature breadth.

## Consequences

- We own the code: any SOCKS edge case is ours to fix, but there is no opaque
  third-party transport in the security-sensitive path.
- Scope is deliberately narrow (no BIND, no UDP ASSOCIATE, no auth). If a real
  need appears it's logged in `docs/backlog.md`, not added pre-emptively.
- The server is supervised by the foreground service and the bounded reconnect
  policy ([ADR-0007](0007-bounded-auto-reconnect.md)).
