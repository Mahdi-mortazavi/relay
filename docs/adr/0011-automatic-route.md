# ADR-0011: Choose the PC's route automatically, and keep choosing

**Status:** Accepted
**Date:** 2026-10-21

Builds on [ADR-0010](0010-forward-through-a-local-proxy.md).

## Context

ADR-0010 made the one arrangement that gives a PC both the connection and the
VPN on a capturing phone *reachable*: exclude Relay in the VPN app's per-app
list, and point Relay at that app's local SOCKS5 port. It did not make it
*happen*. Relay found the port, then offered it on a line inside Advanced, and
"opening a section is not consent to reroute a connection" kept it at an offer.

Five weeks of reports since say what that cost. #124, #125 and #126 are all one
story: with a VPN on the phone the PC finds the phone and then cannot connect,
or connects and does not get the VPN. The phones that need the proxy are
exactly the ones where the person already did the hard part — excluded Relay,
because the banner told them to — and then got the phone's bare connection,
because the second half was a tap in a section they had no reason to open.
That is the opposite of "never let the user down".

Three things were also true that the offer could not respond to:

1. **Which route is right changes during a session.** The VPN app is opened
   after sharing starts, or killed, or switched between full-tunnel and
   proxy-only mode; the person excludes Relay mid-session. The upstream was
   fixed at `StartVia` and could only change by restarting the endpoint, which
   drops the PC.
2. **Proxy-only VPN modes exist and are the cleanest fix for group 3.** Oblivion
   offers `SOCKS5 only`, and the v2ray- and sing-box-based clients this
   product's users run commonly offer a proxy-only mode too: no system VPN at
   all. Then nothing captures Relay's replies — the PC connects
   every time — and the VPN exists only behind a loopback port. The phone's own
   route there is never the VPN; the proxy is the only way to share it.
3. **Android says whether Relay is inside the VPN.** The default network an app
   is given is the one for its own UID: the VPN for an app inside it, the
   underlying network for one the VPN excluded. That is an observation, not the
   prediction the reply-path probe was (`vpn-compat.md` → "The probe predicts;
   it does not observe").

## Decision

**Relay chooses the route itself while sharing, and the choice is the default.**

```
typed proxy in Advanced      → that proxy, always, never dropped
automatic off                → the phone's own route
Relay inside the VPN         → the phone's own route   (it already is the VPN)
Relay outside, proxy answers → the VPN app's proxy     (the way back in)
Relay outside, none answers  → the phone's own route   (keep the PC online)
```

`RouteChoice` holds that table and is pure, so it is decided on the JVM.

**It is decided again every 10 seconds** (`RouteChoice.POLL_MS`) and the endpoint
is re-pointed without a restart: `Endpoint.SetUpstream` swaps the forwarders'
upstream behind an atomic pointer. Keys, port, peer and handshake are untouched.
New flows take the new path; open TCP streams finish on the old one, because TCP
cannot change egress mid-life, and idle UDP flows are reaped within a minute.

**A working proxy is not dropped on one miss.** Giving it up sends the PC out by
the phone's bare connection, so two looks in a row must fail
(`MISSES_BEFORE_DROPPING`) — twenty seconds of a VPN app that has closed its
port, not one slow answer from a busy one. Every other change is taken at once:
a proxy appearing puts the PC on the VPN, and Relay landing inside the VPN means
the route already is the VPN.

**A proxy Relay chose for itself never stops sharing from starting.** If the
endpoint will not start through it, Relay starts on the phone's route and logs
why. A *typed* proxy keeps its old behaviour — a start failure is an error —
because that is a person saying where traffic must go.

**The screen says what is happening.** Advanced shows the route in force and
why ("Now: through the VPN proxy at 127.0.0.1:1819"), and every choice and
switch is a `Route chosen` / `Route switched` line in the diagnostic log with
the reason, so a report answers "did the PC get the VPN?" without a round trip.

## Consequences

**Good.** On a capturing VPN, the advice is now one step instead of two:
exclude Relay (or switch the VPN app to proxy-only mode) and the PC gets the
VPN. On a VPN that already lets Relay through, nothing changes. Nothing new
leaves the phone — every probe is a loopback connection to another process on
the same device, as in ADR-0010 — so `CLAUDE.md`'s local-only rule holds.

**What changed for existing users.** Before 2.10.0 an empty field meant "the
phone's route, always". It now means "automatic". The only phones where this
moves traffic are ones where Relay is outside every VPN *and* a loopback SOCKS5
port grants UDP ASSOCIATE — i.e. a VPN or proxy app the person installed and is
running. **Automatic off** restores the old behaviour exactly.

**Falling back is a choice, and it is the phone's.** When a proxy that was
carrying the PC disappears, automatic keeps the PC online over the phone's own
connection, as the phone's other excluded apps already are. Someone who wants
the opposite — nothing unless it goes through the VPN — types the proxy in, and
it is never dropped; on the laptop, the kill switch (2.10.0, #165) holds traffic
when the phone stops answering instead of letting the PC fall back to its own
connection.

**What it still cannot do.** A VPN with no LAN exemption, no per-app exclusion
and no local proxy leaves a phone where the PC can have the connection or the
VPN, not both. Excluding Relay is still a tap in someone else's app; nothing in
Android lets another app make it (`vpn-compat.md` → "Excluding Relay cannot be
automated").

**Hardware.** The Go suite proves the hot swap, both ways, against a fake
proxy; the Kotlin suite proves the table and the hysteresis. What a phone must
still show — a switch under a real VPN app opening and closing its port while a
PC downloads — is listed in `docs/testing.md`.
