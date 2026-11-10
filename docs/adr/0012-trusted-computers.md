# ADR-0012: Trusted computers — remember a PC by a key it holds, not a name it types

**Status:** Accepted
**Date:** 2026-11-02

Builds on [ADR-0009](0009-full-mode-only-and-code-pairing.md). Narrows one rule
in `shared/pairing-beacon.md` → "Approval", which said approvals are never
persisted.

## Context

Pairing asks the person holding the phone to approve the computer every time
sharing starts. That is the right default: the two-digit code is a selector, not
a secret, and the prompt is the only thing between a stranger on the same Wi-Fi
and the phone's data. It is also tedious for the one computer a person pairs
with every day, at the same desk, from the same phone.

The obvious fix is "remember this computer", and the obvious way to build it is
wrong. What the phone knows about a computer is an IP address and a name the
computer typed for itself. The address is a DHCP lease and changes hands; the
name is whatever the sender chose. Remembering either turns "a person approved
MAHDI-LAPTOP once" into "anything on this network that calls itself MAHDI-LAPTOP
gets a tunnel to this phone with no prompt, ever" — and the prompt was the whole
security model. That is also why the contract said approvals are *never
persisted*, with a reason: a remembered decision on a network you have left is a
decision made on the wrong network.

## Decision

**A computer is remembered by a key it holds**, and only by that.

- Each Windows install makes a **P-256 key pair** on first use and keeps the
  private half. The public half rides in the pairing request as an optional
  `key`.
- A phone that receives a `key` always replies with a random **challenge**; the
  PC signs it, and the phone verifies. Possession of the private key — not a
  name, not an address — is what is recognised.
- Only a person can add a computer to the list, by choosing **Always allow** on
  the prompt for a computer that has just proven its key. The prompt for a
  computer that has not is the old one, without that choice.
- A computer on the list that proves its key skips the prompt. Nothing else
  about pairing changes: one client per session, per-pairing tunnel keys,
  address decisions that last one session.
- The phone shows the list in Advanced, with a short fingerprint so two
  computers with the same name can be told apart, and removes one or all.

### Why P-256 and ECDSA

Because both apps already have it and nothing has to be added. Android's
`minSdk` is 26, and Ed25519 in `java.security` arrives in API 33; ECDSA on P-256
is in every Android release and in .NET (`ECDsa`). No dependency, no
`BouncyCastle`, no native code. The signature is the fixed-width `r ‖ s` form,
not DER, so there is one byte layout to pin in a shared vector rather than two
encoders to agree.

### Why the challenge is always sent

If the phone challenged only keys it trusted, then any device on the network
could send a key and learn from whether it was challenged which computers are on
the list. Sending the challenge for every well-formed key makes the phone's
answer independent of its list. An unproven or unanswered challenge is not an
error; the request becomes an ordinary one.

### Why no key means no "Always allow"

A PC that offers a key it cannot prove would otherwise be enrollable by anyone
who had seen that key on the wire. The key is not secret — it is sent in clear
on a LAN. Possession is the only thing that is.

## Alternatives rejected

- **Remember by name or address.** Spoofable by anyone on the network, as above.
  Rejected outright; it would have made the prompt decorative.
- **Remember by name and Wi-Fi network.** Still spoofable by anyone on that
  Wi-Fi, which is the exact attacker the prompt exists for.
- **A shared secret typed once on both devices.** Strong, but it is a second
  pairing ceremony to avoid a first one. Worse than the prompt it replaces.
- **Ed25519.** Cleaner, but needs API 33 or a new dependency.
- **Encrypting the whole exchange.** The right long-term answer to the other gap
  below, and a much larger change; out of scope here.

## Consequences

- **A trusted key is as safe as the file holding its private half.** On Windows
  that is a file under the user's own `%LOCALAPPDATA%`, readable by that user's
  processes, which is where DPAPI's `CurrentUser` scope would put it too. A lost
  or stolen laptop is removed from the phone's list; there is no remote
  revocation, because Relay has no server and will not grow one.
- **It authenticates the PC to the phone, not the phone to the PC.** A PC still
  takes a configuration from whatever answered at the address a broadcast
  beacon named. That was already true and is not made worse; it is not fixed
  here either.
- **The exchange is still not encrypted.** The key, challenge and proof are
  public by design and reveal nothing that helps an attacker; the tunnel keys
  that follow are as exposed as they were.
- **Reinstalling Windows, or deleting the key file, makes a new identity**, and
  the phone asks again. That is the correct failure.
- **The list is bounded at 16**, replacing the computer that paired longest ago.
  Losing trust costs one prompt, which is the safe direction to fail in.
- **Two small contract additions, both optional.** `key` in the request, and a
  `challenge`/`proof` pair of lines. An older phone ignores the key and never
  challenges; an older PC sends none. Nothing in either direction breaks.
- **Removing a computer does not cut a live tunnel.** Its configuration is
  already in its hands; stopping sharing ends it, as it always has.
