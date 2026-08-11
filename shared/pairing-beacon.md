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
  phone does not gather it on this path.
- Two phones can therefore land on the same code. That case has an answer
  already — see `ERR_CODE_AMBIGUOUS` below, where the PC shows both device
  names and asks which one.

## Discovery, on the listener

1. Bind UDP `47654` with address reuse, and join in on every interface.
2. Keep the most recent beacon per `(host, port)`. Drop an entry when its last
   beacon is older than **5 s**, or immediately on `state: "stopped"`.
3. When the user enters two digits, connect to the entry whose `code` matches.
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

## Compatibility

The eight-character typed code stays supported for one release: a Windows build
that predates this contract has no listener, and a phone that predates it sends
no beacon. The Windows app accepts a two-digit code, an eight-character code, or
a QR, and picks the path from what was typed.
