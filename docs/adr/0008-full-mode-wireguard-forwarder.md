# ADR-0008: Full Mode — phone as a userspace WireGuard forwarding endpoint

**Status:** Accepted · **Date:** 2026-07-07

## Context

Full Mode (§4.1) needs the **phone** to be a WireGuard endpoint that the Windows
client dials, with the phone forwarding the client's TCP **and** UDP out through
the phone's active VPN. The spec names `com.wireguard.android` and
WinTun/`wireguard-go` (§5.1–5.2).

Two realities shape the design:

1. **The phone is the server, and it's unrooted.** `com.wireguard.android`'s
   `GoBackend` is *client*-shaped: it stands up a `VpnService` that routes the
   **device's own** traffic to a remote peer. It does not expose "accept a peer
   and NAT its traffic to the internet," and unrooted Android has no iptables/NAT
   to forward packets from a WireGuard interface upstream.
2. **No local test loop.** The live tunnel can only be verified on real hardware
   (the user accepted this when choosing the full build).

## Decision

- **Phone (server):** embed **`wireguard-go`** with its **`tun/netstack`**
  (gVisor userspace network stack) as a small Go module, compiled to an Android
  library via **gomobile**. wireguard-go terminates the WireGuard tunnel in
  userspace (official WireGuard crypto — no hand-rolled crypto, satisfying §5.1)
  and the netstack turns inbound IP packets from the client into ordinary
  outbound **sockets on the phone** — which ride the phone's default network, and
  therefore its VPN, exactly like the Fast-Mode SOCKS path (ADR-0006). No
  `VpnService`, no root, no NAT tables required.
- **Windows (client):** bundle the official **`wintun.dll`** and run a userspace
  **`wireguard-go`** tunnel that dials the phone's endpoint using the per-pairing
  keys from the QR `wg` block; the app sets the adapter address, DNS, and routes,
  all inside the existing transactional teardown (safety invariant #2).
- **Keys are per-pairing and ephemeral:** the phone generates both key pairs at
  advertise time using `com.wireguard.android`'s `KeyPair` (official), embeds the
  server public key + the client private key in the QR, and **discards them on
  disconnect** (§4.2).
- **Mode is a payload/UI concern, not a new state:** the Fast/Full toggle selects
  which server the phone brings up and which `mode` the QR carries; the shared
  five-state machine is unchanged, so switching modes needs no app restart
  (AC3.3) — stop the current server, start the other, re-advertise.

## Consequences

- Crypto and the WireGuard protocol come entirely from official `wireguard-go`;
  Relay's code is glue (config, lifecycle, netstack dial-out).
- **CI proves** the Go module + AAR build, the tunnel config assembly, key
  handling, the toggle, and the state machine. **Only hardware proves** the live
  UDP tunnel and latency (AC3.1/AC3.2) — documented as such in `docs/testing.md`.
- Windows Full Mode requires elevation to create the WinTun adapter and set
  routes — a genuine difference from Fast Mode's per-user proxy; surfaced in the
  UI and `docs/release.md`.
- gomobile + NDK and bundling `wintun.dll`/`wireguard-go` add real weight to CI;
  the build steps live in `ci.yml` and the release workflows.
