# ADR-0010: Let the phone forward through a local proxy

**Status:** Accepted
**Date:** 2026-09-16

## Context

Relay's forwarders open ordinary Go sockets, so the traffic they carry takes the
phone's default route — through its VPN when one is up. `netstack.go` has always
said so in as many words: *"which is the entire point of the feature."*

An Android VPN routes by UID, and that is the problem. A full-tunnel VPN claims
every UID but its own, so it also swallows Relay's **replies to the PC**. The
PC reaches the phone, pairs, receives a configuration, starts its tunnel, and
the handshake never comes back. `vpn-compat.md` has documented this since 2.7.1;
`HandshakeWatch` and the beacon's `blocked` field exist to explain it.

The advice every VPN's own support gives is to exclude the app in a per-app or
split-tunnel list. It works. Measured on hardware on 2026-09-16, with Oblivion
(Cloudflare WARP) full-tunnelling a Samsung SM-A307FN:

```
before:  ip route get 192.168.198.44 uid 10698  ->  dev tun0    src 198.18.0.1
after:   ip route get 192.168.198.44 uid 10698  ->  dev rndis0  src 192.168.198.4
```

The PC connected in seconds. And then:

```
ip route get 1.1.1.1 uid 10698  ->  dev wlan0  via 192.168.1.1   (Relay)
ip route get 1.1.1.1 uid 10103  ->  dev tun0   src 198.18.0.1    (any other app)
```

**Excluded from the tunnel, Relay forwards over the phone's ordinary
connection.** The PC gets the phone's internet and not the phone's VPN, because
Relay cannot send through a tunnel it has been shut out of. Confirmed from the
far end: the laptop's public address was the phone's own, not the WARP exit.

So the product had two states and neither was the one people wanted:

| Relay in the tunnel | Relay excluded |
|---|---|
| The phone cannot answer the PC. No connection at all. | The connection works. The PC does not get the VPN. |

Three ways out were considered.

**Escape the capture from inside the app.** Ruled out, and not newly: all three
routes were tried on hardware and are recorded in `vpn-compat.md`.
`Network.bindSocket` fails `EPERM` for an app inside a VPN, binding the source
address leaves the UID rule deciding the interface, and `SO_BINDTODEVICE` needs
`CAP_NET_RAW`. The real escape needs
`CONNECTIVITY_USE_RESTRICTED_NETWORKS`, which is signature-level.

**Ask the VPN for a local-network exemption.** This is the best outcome where it
exists — Relay's replies escape, its forwarded traffic still goes through the
tunnel — but it is a setting in someone else's app, offered by some clients and
not others. Oblivion, to name the one this was measured on, offers only
`SOCKS5 only` and `Full device tunnel`. It stays the first thing Relay's copy
recommends and it cannot be relied on.

**Forward through the VPN's own local proxy.** The clients this product's users
actually run — v2ray, sing-box, Hiddify, Oblivion — nearly all expose a local
SOCKS5 port, because that is how they serve apps that are not routed.

## Decision

**Relay can be told to forward the PC's traffic through a SOCKS5 proxy on the
phone**, configured in Advanced and empty by default.

With Relay excluded from the VPN *and* pointed at the VPN's local port:

- Relay is outside the tunnel, so its replies reach the LAN and the PC connects;
- the traffic it forwards enters the tunnel through the proxy, so the PC gets
  the VPN.

Both, which no other arrangement gives.

Implementation notes that are decisions, not details:

- **The SOCKS5 client is hand-written** (`wg/upstream.go`). `golang.org/x/net/proxy`
  has no UDP, and Relay's claim is that it carries TCP *and* UDP so calls and
  games work. A proxy mode that silently dropped every datagram would quietly
  turn the product into a browser tunnel. RFC 1928's UDP ASSOCIATE is
  implemented alongside CONNECT.
- **No authentication is offered.** These proxies listen on loopback on the same
  phone. A username and password would be a field that protects nothing.
- **`Start` and `StartEndpoint` keep their signatures**, with `StartVia` and
  `StartEndpointVia` beside them. gomobile generates the Kotlin binding from the
  signature, and the Kotlin side looks the new one up reflectively so an app
  built against an older AAR keeps working instead of losing Full Mode.
- **Empty is the default and means the default route** — exactly what every
  release before 2.8.6 did.

## Consequences

**Good.** The one configuration people actually want becomes reachable. It needs
no permission Relay does not have, no new network destination — the proxy is on
the same phone — and nothing leaves the two devices, so `CLAUDE.md`'s local-only
rule is untouched.

**The cost.** It is a setting, and settings are surface. It is in Advanced, it is
empty by default, and the phones that never see a VPN capture never need it.

**What it does not fix.** A VPN client with no local proxy and no LAN exemption
is still a phone where you choose between the connection and the VPN. Relay can
now say which arrangements exist; it cannot add a port to somebody else's app.

**Unproven at the time of writing.** The Go suite covers CONNECT, UDP ASSOCIATE,
header framing and the refusals against a fake proxy. Carrying real traffic
through a real VPN client's port has not been done on hardware yet, and
`docs/testing.md` records that.
