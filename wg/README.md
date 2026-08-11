# `wg` — the phone side of Full Mode

A userspace WireGuard endpoint that accepts one peer (the PC) and forwards its
TCP **and** UDP out through ordinary sockets on the phone.

Contract: [`docs/adr/0008-full-mode-wireguard-forwarder.md`](../docs/adr/0008-full-mode-wireguard-forwarder.md).

## Why this is not just wireguard-go

Every WireGuard library for Android is client-shaped: it stands up a
`VpnService` and routes the *device's own* traffic to a remote peer. Relay needs
the mirror image — the phone is the server, and an unrooted phone has no
iptables and no NAT to forward packets with.

`wireguard-go` terminates the tunnel, and a gVisor network stack turns the
peer's inbound IP packets into normal outbound sockets on the phone. Those
sockets take the phone's default route, and therefore its VPN, exactly as the
Fast Mode SOCKS path does.

`wireguard-go`'s own `tun/netstack` cannot do this: it only dials and listens on
addresses it owns, which is what a client needs. A forwarding endpoint has to
accept traffic addressed to *anywhere* — the PC asks for `142.250.0.0:443` and
nothing in the process holds that address. So the stack is built here, which
gives access to gVisor's TCP and UDP forwarders, promiscuous mode and spoofing.

No part of the WireGuard protocol is implemented here. Crypto is entirely
`wireguard-go`'s; this is configuration, lifecycle and dial-out glue.

## Building the Android library

```
scripts/build-wg-aar.sh          # needs Go and an NDK; ANDROID_HOME set
```

It writes `android/app/libs/relaywg.aar`, which Gradle picks up automatically.
CI and the release workflow run the same script, so there is one build of this
library rather than one in a workflow and a different one on a desk.

A checkout without the AAR still builds the app; Full Mode then reports itself
unavailable and the toggle is disabled, which is why no Android developer needs
a Go toolchain. Builds that ship pass `-PrelayRequireWg=true` so that tolerance
cannot quietly produce a release missing a mode the UI offers.

Two flags in that script are load-bearing:

- `-extldflags=-Wl,-z,max-page-size=16384` — Android 15 runs some devices with
  16 KB memory pages, and a 4 KB-aligned shared library does not load there at
  all. gomobile's default is 4 KB and every emulator image uses 4 KB pages, so
  nothing in the test lab would ever notice. The script checks the ELF headers
  afterwards rather than trusting the flag.
- `-s -w` — strips symbols. 18 MB to 9.6 MB, which is 10 MB off the universal
  APK.

## Tests

`go test ./...` stands up the real endpoint, dials it with a real `wireguard-go`
client over loopback UDP, and pulls a page — and an echoed datagram — from
servers reachable only outside the tunnel. If bytes come back, the tunnel
terminated, the stack accepted a packet addressed somewhere it does not own, a
forwarder opened a real socket, and the reply made it home.

It also pins the configuration the *phone* sends: `contract_test.go` feeds the
exact string from `/shared/test-vectors.json` to a real device, while the
Android suite asserts `WgConfig.serverConfig` produces that same string. Neither
check is worth much alone — one is agreement on something no device accepts, the
other is validity nobody produces — and the gap between them is where Full Mode
sat broken: the app was sending `wg-quick` INI to an IPC parser.

The same suite is cross-compiled for `android/amd64` and run on an emulator by
`.github/scripts/wg-device-test.sh`. Android is a different libc, linker and
sandbox, and this package is almost nothing but system calls, so "it passes on
Linux" is not the claim anyone downloads the app for.

What none of that covers is a real phone's radio, a real Windows adapter, and
latency under load — those still need hardware, as the ADR says.

One thing the tests learned the hard way: destinations must not be on
`127.0.0.0/8`. gVisor drops packets addressed to loopback that arrive on an
ordinary NIC, which fails the test for a reason unrelated to the endpoint — and
a phone forwarding to the internet never sees a loopback destination anyway.
