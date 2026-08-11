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
- **The configuration crosses in wireguard-go's IPC dialect, not `wg-quick` INI.**
  `WgConfig.serverConfig` writes flat `key=value` lines with **hex** keys,
  because that is what `Device.IpcSet` reads; the readable INI form is rejected
  outright. The exact string is pinned in `/shared/test-vectors.json`, asserted
  by the Android suite and applied to a real device by the Go suite.
- **The tunnel is 10.13.37.0/24**: the phone answers on `10.13.37.1`, the one
  client is `10.13.37.2`, and the peer is routed at `10.13.37.2/32`. Written down
  in three places — `WgConfig`, `relaywg`, `WgClientConfig` — and all three must
  agree, because `allowed_ip` is cryptokey routing: a mismatch gives a tunnel
  that handshakes and then carries nothing.
- **No approval prompt in Full Mode.** Fast Mode's SOCKS port is unauthenticated,
  so an unknown address has to stop and ask the person holding the phone
  (`/shared/pairing-beacon.md`). Full Mode's client authenticates with a
  32-byte private key that existed only inside one QR code; a peer that
  completes a handshake is by definition the device that was shown the code.
  Asking again would be theatre.
- **"A client is connected" comes from the handshake.** A UDP endpoint has no
  accepted sockets to count and answers identically whether or not anyone is
  behind it, so the phone polls the device's own `last_handshake_time_sec` and
  treats a peer as present for three minutes after one — the same window
  `wg(8)`'s tooling uses, with rekeying at two minutes to refresh it.
- **Mode is a payload/UI concern, not a new state:** the Fast/Full toggle selects
  which server the phone brings up and which `mode` the QR carries; the shared
  five-state machine is unchanged, so switching modes needs no app restart
  (AC3.3) — stop the current server, start the other, re-advertise.

## Consequences

- Crypto and the WireGuard protocol come entirely from official `wireguard-go`;
  Relay's code is glue (config, lifecycle, netstack dial-out).
- **CI proves** more than this ADR originally expected. The endpoint is
  exercised end to end in a process: a real `wireguard-go` client dials the real
  endpoint over loopback UDP and pulls an HTTP response and an echoed datagram
  from servers reachable only outside the tunnel. So the tunnel terminating, the
  netstack accepting a packet addressed somewhere it does not own, the forwarder
  opening a real socket, and TCP **and UDP** making the round trip are all
  covered without hardware.
- **The Android app ships the library.** `scripts/build-wg-aar.sh` builds it and
  drops it in `android/app/libs`, where Gradle picks up any AAR; CI and the
  release workflow run that same script. A checkout without it still builds and
  reports Full Mode unavailable, which is what keeps a Go toolchain and an NDK
  off every Android developer's machine — but any build that *ships* passes
  `-PrelayRequireWg=true`, so a release cannot silently omit a mode the UI
  offers. That omission is precisely what happened for the first four releases
  of this feature.
- **It is proven on Android, not only on Linux.** The endpoint's own suite is
  cross-compiled for `android/amd64` against bionic and run on an emulator
  (`.github/scripts/wg-device-test.sh`), and a device test starts Full Mode
  through the real service and checks from outside the app that the UDP port is
  really held and really released.
- **Only hardware proves** what is left: a phone's radio, a Windows adapter,
  latency under load (AC3.2), and behaviour across a real network change —
  documented as such in `docs/testing.md`.
- **Size.** The Go library adds about 2.8 MB to the arm64 APK and 10.5 MB to the
  universal one, after stripping symbols and dropping `com.wireguard.android`'s
  unused `GoBackend` natives (a second copy of wireguard-go, for the client
  shape Relay does not use). Native segments are linked with
  `max-page-size=16384`: Android 15's 16 KB-page devices refuse to load a 4 KB
  aligned library, and no emulator image in this repo's lab would catch that.
- Windows Full Mode requires elevation to create the WinTun adapter and set
  routes — a genuine difference from Fast Mode's per-user proxy; surfaced in the
  UI and `docs/release.md`.
- gomobile + NDK and bundling `wintun.dll`/`wireguard-go` add real weight to CI;
  the build steps live in `ci.yml` and the release workflows.
