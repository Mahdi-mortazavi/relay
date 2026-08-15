# Pairing beacon and short code — contract (v1)

The typed-code path in [`typed-code.md`](typed-code.md) makes the code carry the
address, which is why it needs eight characters. Reading eight characters off a
phone screen and typing them on a keyboard is the slowest part of setup, and the
one people get wrong.

This replaces it. The phone announces itself on the local network, so the code no
longer has to carry anything — it only has to pick one phone out of the few that
answered. Two digits is enough for that, and two digits is something a person
reads once and types without looking back.

## What the code is, and what it is not

The code is a **selector, not a secret**. Two digits is a hundred possibilities;
treating it as a password would be indefensible. Anything on the same network can
see the beacon and reach the phone's port whether or not it knows the code, which
was equally true of the eight-character code — it was never a secret either, only
an address in disguise.

What makes the pairing safe is the phone: the first time a computer tries to use
the proxy, sharing stops and waits for the person holding the phone to allow it,
with the computer's address shown. See "Approval" below. The code chooses; the
human consents.

## The beacon

While sharing is on, the phone sends a UDP datagram to the broadcast address of
each connected interface, port **47654**, every **1000 ms**, and one final
datagram when sharing stops (`state: "stopped"`), so a listener drops it
immediately rather than after a timeout.

Payload is UTF-8 JSON, one object, no whitespace requirements:

```json
{
  "v": 1,
  "code": "42",
  "mode": "socks5",
  "host": "192.168.43.1",
  "port": 1080,
  "name": "Pixel 4a",
  "state": "sharing"
}
```

| field   | type   | rule                                                             |
|---------|--------|------------------------------------------------------------------|
| `v`     | int    | `1`. A listener MUST ignore any object whose `v` it does not know. |
| `code`  | string | Exactly two digits, `"10"`–`"99"`. See below.                     |
| `mode`  | string | `socks5` or `wireguard`, matching the QR payload's `mode`.         |
| `host`  | string | The address a client should connect to — the phone's address on that interface. |
| `port`  | int    | 1–65535.                                                          |
| `name`  | string | Device name, ≤ 32 chars, for display. MAY be absent.               |
| `state` | string | `sharing` or `stopped`.                                           |
| `pairingPort` | int | 1–65535. Where to ask for a configuration. MAY be absent, and then this phone can only be paired by QR. |

The keys a `mode: wireguard` phone needs are **not** in the beacon and never can
be: a beacon is broadcast, unauthenticated, and readable by anything on the
network. A listener that tried to build a tunnel out of the beacon's fields
alone would produce a configuration the other end refuses.

The code still works, because the beacon carries a `pairingPort` instead of
keys, and the configuration is fetched over a short TCP exchange that the person
holding the phone has to allow — see [The pairing exchange](#the-pairing-exchange)
below, and [ADR-0009](../docs/adr/0009-full-mode-only-and-code-pairing.md) for
why it is shaped this way. A beacon that omits `pairingPort` announces a phone
that can only be paired by QR; a listener MUST show it, MUST NOT offer it as
connectable by code, and MUST say the QR is the way in
(`ERR_FULL_MODE_NEEDS_QR`) rather than reporting the code as invalid. Reporting
a correct code as invalid is how a working pair of apps comes to look
incompatible.

A datagram that fails any rule is dropped in silence. Beacons are not
authenticated, so a listener MUST treat every field as untrusted display data:
`name` in particular is attacker-controlled and must never be interpolated
anywhere it could be executed.

## The code

- Two digits, `10`–`99`. Ninety values, and none of them start with a zero, so
  there is no "did I need to type the leading zero" moment.
- Drawn with a cryptographically-seeded RNG at the start of each sharing
  session, and kept for the life of that session.
- Drawn blind. The phone does not survey the network first: listening long
  enough to be useful blocks the thread that starts sharing, and a frozen app
  is a worse outcome than a rare collision. `PairingCode.draw` accepts a set of
  codes to avoid, for a caller that already has that knowledge cheaply, but the
  phone does not gather it on this path. When given such a set it chooses
  uniformly from the codes still free, so it cannot hand back a code it was
  told to avoid while free ones remain.
- Two phones can therefore land on the same code. That case has an answer
  already — see `ERR_CODE_AMBIGUOUS` below, where the PC shows both device
  names and asks which one.

## The probe

A listener MAY also ask, instead of only waiting to be told. It sends this
datagram to the broadcast address of each of its own interfaces, port **47654**:

```json
{"v":1,"probe":1}
```

A phone that is sharing MUST answer it with one ordinary beacon, sent
**unicast** back to the sender's address and port — not broadcast, or the answer
would reach every listener but the one that asked. A phone that is not sharing
answers nothing.

This is not an optimisation. Windows Firewall blocks *unsolicited* inbound UDP
to an unelevated app by default, and Relay's installer is per-user and cannot
add a firewall rule. Passive listening therefore fails on many PCs with no
symptom the user can act on: the phone shows a code, the PC says no phone has
it. A datagram the PC sent first creates the outbound state entry that lets the
answer back in, so this is the path that works on a machine nobody configured.

Rules on both sides:

- A probe has no `code`, so a beacon parser rejects it. A responder MUST
  identify a probe by `probe` being present and truthy, and MUST ignore
  everything else it receives — including its own broadcasts, which the host
  loops back.
- A probe carries nothing about the sender beyond its source address, and the
  answer goes only there. Nothing in it is trusted.
- A listener SHOULD probe about once a second while it is actually looking, and
  MUST NOT rely on probing alone: on a network where broadcast reaches the phone
  but not back, the passive path is the one that works.

### The answer cannot always be sent

A responder MUST send the answer out of the interface that owns the address it
is advertising in `host`. **On Android it cannot do so while the phone's own VPN
captures the app** — and that is Relay's normal case, not an edge case.

Android routes an app's traffic by UID. A full-tunnel VPN claims every UID but
its own, so the responder's *unicast* answer is routed to the VPN's exit and
never reaches the PC, while its *broadcast* beacon, being link-scoped, bypasses
the tunnel and arrives normally. `send()` reports success either way, and
nothing is logged: the datagram simply goes the wrong way.

There is no app-level escape, and all three obvious ones were tried on hardware:

| Attempt | Result |
|---|---|
| `Network.bindSocket` to the Wi-Fi network | `EPERM` — an app inside a VPN may not bind outside it |
| Binding the source address to the LAN address | routing unchanged; the UID rule still wins |
| `SO_BINDTODEVICE` | needs `CAP_NET_RAW` |

Escaping requires `CONNECTIVITY_USE_RESTRICTED_NETWORKS`, which is
signature-level and not available to a normal app.

This is a real conflict between two of Relay's own requirements rather than an
oversight. The proxy has to be *inside* the VPN or the shared traffic would not
use the VPN at all — which is the entire product — and anything inside the VPN
cannot answer a probe.

Consequences a listener has to be built around:

- Passive discovery is unaffected, so a PC that can receive unsolicited inbound
  UDP finds the phone normally. This is why the fault is invisible on any PC
  that has ever been told to allow the listener through.
- A PC that depends on the probe — the default Windows Firewall case, which is
  every fresh install — will **not** find a phone whose VPN captures it, however
  long it waits.
- The QR and the eight-character code carry the address and need no discovery,
  so they remain the paths that always work.

A responder that knows it is in this state SHOULD say so rather than present two
digits that cannot be resolved; how the apps should surface it is not yet
decided and is tracked in `docs/testing.md`.

## Discovery, on the listener

1. Bind UDP `47654` with address reuse, and join in on every interface.
2. Probe (above) while the pairing screen is open.
3. Keep the most recent beacon per `(host, port)`. Drop an entry when its last
   beacon is older than **5 s**, or immediately on `state: "stopped"`.
4. When the user enters two digits, connect to the entry whose `code` matches.
   - No match → `ERR_CODE_NOT_FOUND`: the phone is not sharing, or is on another
     network.
   - More than one match → `ERR_CODE_AMBIGUOUS`: show the device names and let
     the user pick. Ninety codes and two phones collide about one time in
     forty-five, so this is uncommon but not rare enough to leave unhandled.

## The pairing exchange

Two digits select a phone. This is how the PC then gets a configuration it can
actually dial, without a key ever touching a broadcast.

The phone listens on TCP `pairingPort` while it is sharing. One request per
connection, UTF-8 JSON, one object per line, `\n`-terminated.

**The PC asks:**

```json
{"v":1,"pair":1,"name":"MAHDI-LAPTOP"}
```

`name` is optional, ≤ 32 chars, and is shown to the person being asked so the
prompt names a computer rather than only an address. It is attacker-controlled
display data like every other name here.

**The phone answers, once, after the person decides.** On Allow:

```json
{"v":1,"ok":1,"host":"192.168.1.14","port":51820,
 "wg":{"serverPublicKey":"…","clientPrivateKey":"…",
       "allowedIps":"0.0.0.0/0","endpointPort":51820,"dns":"1.1.1.1"}}
```

The `wg` object is **exactly** the one in
[`qr-payload.schema.json`](qr-payload.schema.json), so both paths hand the
client the same structure and neither platform needs a second parser. On Deny,
or on the 60-second timeout:

```json
{"v":1,"error":"ERR_PAIRING_DENIED"}
```

Then the phone closes the connection, in both cases.

Rules:

- The phone MUST NOT send keys before the person has allowed it. A request that
  is denied or times out learns nothing beyond the fact that something is
  listening, which the beacon already said.
- **One client per sharing session.** The endpoint is configured with a single
  peer, so the QR and this exchange hand out the *same* client key — they are
  two ways to receive one configuration, not two configurations. A second
  computer pairing while a first is connected takes the tunnel over rather than
  joining it. Fast Mode allowed several clients at once; this does not, and a
  listener MUST NOT present it as though it does.
- The phone MUST answer a `v` it does not know with
  `{"v":1,"error":"ERR_PAIRING_VERSION"}` rather than silence, so a newer PC
  learns it is talking to an older phone instead of waiting out a timeout.
- A connection that sends nothing within **10 s** is closed. This port is
  reachable by anything on the network, so an idle socket is not held open for
  it.
- The PC MUST treat everything it receives as untrusted until the tunnel
  handshakes. A configuration that cannot be parsed, or whose `wg` block is
  incomplete, is `ERR_QR_INVALID` — the same code the QR path uses, because it
  is the same failure: the phone described a tunnel this client cannot build.
- Keys are per pairing. Stopping sharing discards them, so a configuration
  fetched in a previous session is dead — which surfaces as
  `ERR_WG_NO_HANDSHAKE`, not as a successful connection.

**What this is worth, and what it is not.** Anything on the LAN can open this
port, exactly as anything on the LAN could open Fast Mode's SOCKS port. The code
is a selector, not a secret, and the exchange is not encrypted. What stands
between a stranger and your connection is the person holding the phone —
unchanged from the design this replaces, and the reason the prompt shows the
address and the code that was used.

## Approval

A pairing request is held, and the phone asks the person: *"Allow this computer
to share your connection?"* with the requesting address, the name it gave if it
gave one, and the code that was used.

- Allowed → the phone mints this pairing's keys and sends the configuration.
- Denied → `ERR_PAIRING_DENIED`, and further requests from that address are
  refused without asking again for this session.
- No answer within **60 s** → treated as denied, so a phone in a pocket fails
  closed.

Approval state is per sharing session and never persisted: stopping and starting
sharing asks again. This is deliberate — a remembered decision on a network you
have since left is a decision made on the wrong network.

**This gates the code path, not the tunnel.** A client that already holds a
configuration — from a QR, or from an earlier allowed exchange in this session —
authenticates with a 32-byte private key that existed nowhere but in that one
delivery, so a peer completing a WireGuard handshake *is* the device that was
given it. There is nothing left for a prompt to establish there, and one would
only teach people to tap Allow without reading it. The prompt belongs where a
key is about to be **handed out**, which is exactly and only this exchange.

## Compatibility

The eight-character typed code ([`typed-code.md`](typed-code.md)) still works,
but it is no longer what either app leads with. The phone shows it only when it
could not announce itself at all — which means the phone MUST actually check:
it draws a short code, and shows it only if it has at least one interface with a
broadcast address or has bound the probe port. Showing two digits that no PC can
resolve is worse than showing eight that any PC can decode offline, and for
several releases the phone showed them unconditionally, so the "could not
announce" path documented here did not exist in either app. The PC's code box
asks for two digits and
keeps the long code behind a link that names the thing the user would be looking
at: *"my phone shows a longer code"*.

That asymmetry is the whole point. A box captioned "the 8-character code" in
front of a phone showing `42` is not a fallback, it is a contradiction — the
user reads it as *these two apps do not go together* and stops. Whatever the
code box asks for has to be the thing the phone is currently showing.
