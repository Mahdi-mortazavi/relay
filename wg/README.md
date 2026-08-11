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

## Tests

`go test ./...` stands up the real endpoint, dials it with a real `wireguard-go`
client over loopback UDP, and pulls a page — and an echoed datagram — from
servers reachable only outside the tunnel. If bytes come back, the tunnel
terminated, the stack accepted a packet addressed somewhere it does not own, a
forwarder opened a real socket, and the reply made it home.

That covers the part of Full Mode that is actually novel. What it does not cover
is a real phone's radio, a real Windows adapter, and latency under load — those
still need hardware, as the ADR says.

One thing the tests learned the hard way: destinations must not be on
`127.0.0.0/8`. gVisor drops packets addressed to loopback that arrive on an
ordinary NIC, which fails the test for a reason unrelated to the endpoint — and
a phone forwarding to the internet never sees a loopback destination anyway.
