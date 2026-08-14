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

## Approval

The first connection from a client address the phone has not seen in this
sharing session is held, and the phone asks the person: *"Allow this computer to
share your connection?"* with the address and, when the beacon was answered, the
code that was used.

- Allowed → the address is remembered for the rest of the session; later
  connections from it are not held.
- Denied → the connection is closed, and further connections from that address
  are closed without asking again for this session.
- No answer within **60 s** → treated as denied, so a phone in a pocket fails
  closed.

Approval state is per sharing session and never persisted: stopping and starting
sharing asks again. This is deliberate — a remembered decision on a network you
have since left is a decision made on the wrong network.

**Fast Mode only.** This whole mechanism exists because Fast Mode's transport is
an unauthenticated SOCKS5 port: anything that can reach it can use it, so the
only thing standing between a stranger and your data is the person holding the
phone. Full Mode's client authenticates with a 32-byte private key that existed
nowhere but inside one QR code, so a peer that completes a WireGuard handshake
*is* the device that was shown that code. There is nothing left for a prompt to
establish, and one would only teach people to tap Allow without reading it.

## Compatibility

The eight-character typed code ([`typed-code.md`](typed-code.md)) still works,
but it is no longer what either app leads with. The phone shows it only when it
could not announce itself at all, and the PC's code box asks for two digits and
keeps the long code behind a link that names the thing the user would be looking
at: *"my phone shows a longer code"*.

That asymmetry is the whole point. A box captioned "the 8-character code" in
front of a phone showing `42` is not a fallback, it is a contradiction — the
user reads it as *these two apps do not go together* and stops. Whatever the
code box asks for has to be the thing the phone is currently showing.
