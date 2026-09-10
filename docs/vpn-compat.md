# VPN compatibility

How Relay composes with the VPN already running on the Android phone.

- **Fast Mode (SOCKS5):** the proxy opens ordinary sockets on the phone; Android routes them through the active system VPN like any app traffic. No interaction with `VpnService` at all.
- **Full Mode (WireGuard):** uses `VpnService`-adjacent machinery; interactions with the user's existing VPN app are non-trivial and will be documented here.

## Why Fast Mode is VPN-agnostic by design

The SOCKS5 server ([ADR-0006](adr/0006-in-repo-socks5-server.md)) forwards each
request by opening a **plain outbound socket** on the phone and resolving domain
names **on the phone**. Android's routing table sends those sockets and that DNS
through whatever network is currently the system default — and when a VPN is up,
the VPN *is* the default network. So the laptop's traffic inherits the phone's
VPN without Relay integrating with any VPN API. There is no `VpnService` use in
Fast Mode, which is exactly why it composes with arbitrary VPN apps: Relay never
competes for the single system VPN slot.

The one thing worth surfacing to the user is the *absence* of a VPN — if no VPN
transport is active when sharing starts, Relay shows the non-blocking
`NO_VPN_ACTIVE` advisory (`VpnStatus` checks `NetworkCapabilities.TRANSPORT_VPN`),
because the user may have intended to share a VPN that is off.

## What people actually report

Three reports on 2.7.1 — [#124](https://github.com/Mahdi-mortazavi/relay/issues/124),
[#125](https://github.com/Mahdi-mortazavi/relay/issues/125),
[#126](https://github.com/Mahdi-mortazavi/relay/issues/126) — all say the same
thing from different angles: with a VPN on the phone, the PC finds the phone and
then cannot connect. #126 sorted the VPN apps into three groups, which is the
most useful framing anyone has given this problem:

| گروه | نشانه | معنی |
|---|---|---|
| ۱ | همه‌چیز کار می‌کند (FSecure) | VPN ترافیک LAN را رها می‌کند |
| ۲ | برنامه می‌گوید نمی‌تواند تونل کند (TLS Tunnel) | تشخیص داده شد و گفته شد |
| ۳ | **پیام تایید اصلاً نمی‌آید** (Seed 4 me) | تشخیص داده شد ولی گفته نشد |

**Group 3 is the one to fix, and its cause is structural.** `VpnCapture.wouldSwallow`
is called from the pairing server's `configuration` step, which runs only after
`accept()` has returned — i.e. after a PC has completed a TCP connection. When
the capture is severe enough that the phone's SYN-ACK never leaves by the LAN
interface, the handshake never completes: no `accept()`, no prompt, and no
warning either. **The case that most needs the message is the only case that
cannot produce it.**

Since 2.8.1 the diagnostic log carries a `Reply path check:` line written when
sharing starts, before any PC is involved, so a report from a group-3 phone
still says which way replies would leave. It is log-only on purpose — the same
probe has said "swallowed" on a link that then carried 35 MB without trouble, so
it is trustworthy enough to triage with and not to alarm anyone with.

### Two settings worth trying before anything else

Both are on the VPN app's side, and either one alone can produce group 3:

- **"Block connections without VPN"** (Android's lockdown mode, in
  *Settings → Network → VPN → ⚙*). It drops everything that is not the tunnel,
  **including replies to your own LAN**. Turn it off and retry.
- **Per-app / split tunnelling** — exclude Relay in the VPN app's own app list.
  This is what the in-app warning already tells people to do, and it is the
  reliable fix when it is available.

Neither is a Relay setting; Android gives an app inside a VPN no way to opt out
of either (`Network.bindSocket` fails `EPERM`, `SO_BINDTODEVICE` needs
`CAP_NET_RAW`). Saying so plainly is better than implying Relay can route around
a policy the system is enforcing on purpose.

## Hardware verification checklist (per VPN app)

Since there is no local device loop, run this from an installable CI artifact
(APK) on a real phone + the Windows installer on a real laptop:

1. Start the VPN app on the phone; confirm it's connected (its notification/icon).
2. Start Relay sharing; pair the laptop (AC1.1).
3. On the laptop, open an IP-check site — the shown IP/country should match the
   **phone's VPN egress**, not the phone's ISP.
4. Toggle the VPN off then on while connected — Relay should keep working (traffic
   follows the new default); a brief drop is absorbed by auto-reconnect (AC2.3).
5. Note any app that force-kills Relay's sockets or blocks LAN (some "kill-switch"
   or "block connections without VPN" settings do this) in the matrix below.

## Matrix

Both modes are now buildable and testable on a phone. Neither column can be
filled from CI: an emulator has no VPN app and no radio, so every cell here
waits on someone with hardware. Full Mode's sockets leave the phone the same
way Fast Mode's do — through the default network, and therefore through any
active VPN — so the answers are expected to match, but expected is not tested.

| VPN app | Fast Mode | Full Mode | Notes |
|---|---|---|---|
| Proton VPN | ⏳ pending hardware | ⏳ pending hardware | Free tier available for testing; check "permanent kill switch" off |
| Cloudflare WARP (1.1.1.1) | ⏳ pending hardware | ⏳ pending hardware | Uses its own VpnService; verify LAN to the hotspot isn't blocked |
| Mullvad VPN | ⏳ pending hardware | ⏳ pending hardware | "Local network sharing" toggle must be **on** for hotspot clients to reach the phone |

Legend: ✅ works · ⚠️ works with a noted setting · ❌ incompatible · ⏳ awaiting a
hardware run from a CI artifact. Update this table from the checklist results;
each ⚠️/❌ must name the exact setting or reason.
