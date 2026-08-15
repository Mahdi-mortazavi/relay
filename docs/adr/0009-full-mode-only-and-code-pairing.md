# ADR-0009: One transport, and a code that works for it

**Status:** Accepted · **Date:** 2026-08-15

Supersedes the two-mode part of [ADR-0001](0001-two-transport-modes.md).

## Context

ADR-0001 shipped two transports. Fast Mode (SOCKS5, ADR-0006) was the default
because it needs no elevation; Full Mode (WireGuard, ADR-0008) existed for UDP.
Two things have since been measured on real hardware rather than reasoned about.

**Fast Mode does not deliver what it promises.** Its transport is a system
proxy, so only applications that honour the system proxy are shared: browsers
mostly do, and games, calls, installers and anything using UDP do not. The
proxy is also a machine-wide setting Relay edits and must put back, which is
this project's most dangerous failure — a stranded SOCKS proxy breaks every app
on the machine, and most of the safety machinery in the Windows client exists
for that one risk. During the August 2026 hardware session the shipped Fast Mode
path was measured working (`curl --socks5` returned the phone's VPN exit while
the laptop's own exit differed), so this is not a decision about a broken
feature. It is a decision about a feature that costs a system-wide mutation, a
rollback protocol, a crash-recovery hook and a whole class of failure, and buys
TCP-only sharing that Full Mode already does better.

**Full Mode delivers, and was proved end to end.** On the same session:
`Find-NetRoute 1.1.1.1` named the Relay adapter, the laptop's egress became the
phone's VPN exit, and 25 MB moved. It changes nothing outside its own adapter,
which vanishes with its process — so there is no rollback to get wrong.

What kept Full Mode second was pairing. Its keys exist only inside the QR
payload, so [`pairing-beacon.md`](../../shared/pairing-beacon.md) had to say a
`mode: wireguard` beacon **cannot be paired from the beacon** — a laptop with no
camera had no way in at all. Meanwhile the two-digit code, the thing that makes
pairing pleasant, worked only for the mode being removed.

## Decision

**One transport: Full Mode.** Fast Mode, the SOCKS5 server, the system-proxy
session, its snapshot/rollback and its crash-recovery hook are removed from both
apps and from `/shared`.

**The two-digit code works for Full Mode**, via a pairing exchange rather than by
putting keys in a broadcast. The beacon still cannot carry a private key — it is
broadcast, unauthenticated, and readable by anything on the network — so the
code selects a phone and a short-lived TCP exchange delivers the configuration:

1. The phone, while sharing, listens on a **pairing port** and announces it in
   the beacon alongside the code.
2. The PC resolves two digits to a phone, opens TCP to that port, and asks.
3. The phone **holds the request and asks the person**, showing the PC's address
   and the code that was used — the same gate Fast Mode used for its first
   connection, and the same 60-second fail-closed timeout.
4. On Allow, the phone mints this pairing's keys, sends the client
   configuration over that connection, and closes it.
5. The PC brings the tunnel up with what it received.

The QR keeps working unchanged and stays the fastest path; the code is the path
for a laptop with no camera, or a phone the camera cannot focus on.

## Consequences

- **Elevation is now on the only path.** Full Mode needs one UAC prompt to
  create the adapter. Relay is otherwise a per-user install that never asks. The
  prompt is therefore part of the normal flow and has to be explained before it
  appears, not after it is dismissed.
- **The proxy risk is gone**, and with it `ProxySession`, `WinInetProxyStore`,
  `ShutdownRecoveryHook`, `--restore-proxy`, and the uninstaller step that calls
  it. Nothing Relay does now outlives its process.
- **The keys are as exposed as the SOCKS port was**, and no more. Anything on
  the LAN can open the pairing port, exactly as anything on the LAN could open
  the SOCKS port; in both designs the person holding the phone is what stands
  between a stranger and the connection. Keys are minted per pairing, sent once,
  never written to disk, and discarded when sharing stops.
- **Fresh keys on every sharing session remain the rule**, which means a QR or a
  code from a previous session is dead. That is what
  [`ERR_WG_NO_HANDSHAKE`](../errors.md) exists to say.
- **UDP works**, which is most of why anyone wanted this: games, calls and
  anything that is not a browser now actually travel.
- Losing the no-elevation path is a real cost. It is accepted because a mode
  that silently shares only some of your traffic, while holding a machine-wide
  setting hostage, is worse than one prompt.
