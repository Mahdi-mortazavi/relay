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

Since 2.8.1 the diagnostic log carries a `Reply path checked` line written when
sharing starts, before any PC is involved, so a report from a group-3 phone
still says which way replies would leave.

### The probe predicts; it does not observe

A log from a 2.8.1 phone settled what that line is worth. In one session the
probe flipped its verdict **four times**, and at 4999 s said:

```
4999.58  Reply path check: a reply to 192.168.1.1 would leave by the VPN, not by 192.168.1.5
5063.84  A PC at 192.168.1.4 asked to pair
5068.53  Sent the configuration to 192.168.1.4
5073.74  Clients: 1
```

Sixty-four seconds after announcing that replies were lost, that phone paired a
PC and carried its traffic. The probe asks the kernel which interface a datagram
*would* take; it cannot ask whether the packet arrives. So it is kept for triage
and it is **log-only** — a banner that fires on a connection which is working is
the reason the next real warning is not believed.

### What raises the banner instead

From 2.8.2 the warning is `PC_GOT_NO_REPLY`, and it is built out of two things
the phone actually observes:

1. a PC took a configuration — which only happens when someone pressed Connect;
2. the WireGuard endpoint has recorded **no completed handshake** in the
   20 seconds since.

Inbound still arrives under a capture; what a capture eats is the reply. "It
asked and nothing came back" is therefore the exact signature of this fault and
of nothing else. The 20 seconds are `handshakeTimeout` in
`wg/cmd/relaywg-client/main.go`, so the phone's banner appears at the same
moment the laptop shows `ERR_WG_NO_HANDSHAKE` — whose text tells the user the
phone is saying so on its own screen. `HandshakeWatchTest` asserts the two
numbers stay equal.

This still does not help group 3, where `accept()` never returns and so no
configuration is ever taken. Nothing inside the app can: the fix there is one of
the two settings below.

### Two settings worth trying before anything else

Either one alone can produce group 3. **They live in different places, and
asking about the wrong one gets a confident wrong answer** — a user asked about
lockdown looked inside FlClash X and NekoBox, because that is where a setting
called "block connections" sounds like it would be:

- **"Block connections without VPN"** — Android's lockdown mode. **Not in the
  VPN app.** It is in *Settings → Network & internet → VPN → the ⚙ beside the
  app's name*, and the toggle only appears at all once **Always-on VPN** is on,
  so an app that does not support always-on shows nothing there. It drops
  everything that is not the tunnel, **including replies to your own LAN**.
  Turn it off and retry.

  **Since 2.8.3 Relay reads this itself** and says so — see below.
- **Per-app / split tunnelling** — this one *is* in the VPN app's own app list;
  exclude Relay there. It reliably restores the connection, **and it costs the
  VPN.** See below.

### Excluding Relay works, and it is not free

Measured on hardware, 2026-09-16, with Oblivion (Cloudflare WARP) full-tunnelling
a Samsung SM-A307FN. Before the exclusion, the kernel routed Relay's replies into
the tunnel and the PC timed out every time:

```
ip route get 192.168.198.44 uid 10698   ->  dev tun0    src 198.18.0.1
```

After adding Relay to the VPN's bypass list and reconnecting the tunnel — the
list is read when the tunnel is *built*, so the change does nothing until then —
it routed correctly and the PC connected in seconds:

```
ip route get 192.168.198.44 uid 10698   ->  dev rndis0  src 192.168.198.4
ip route get 1.1.1.1        uid 10698   ->  dev wlan0   via 192.168.1.1
ip route get 1.1.1.1        uid 10103   ->  dev tun0    src 198.18.0.1   (a normal app)
```

That second line is the whole point. **Relay excluded from the VPN forwards over
the phone's ordinary connection, so the PC gets the phone's internet and not the
phone's VPN.** Relay cannot send through a tunnel it has been shut out of. Every
other app on the phone keeps the VPN; the PC does not.

So the honest order of preference is:

1. **"Allow local network access" / "LAN access"**, if the VPN app has it. Relay's
   replies escape, its forwarded traffic still goes through the tunnel, and the
   PC gets the VPN. This is the only *VPN* setting that keeps both.
2. **Turn off lockdown**, which has the same shape: it restores the local-route
   exemption without taking Relay out of the tunnel.
3. **Exclude Relay per-app, and point Relay at the VPN's local proxy** — see
   below. This is the arrangement that works when the VPN offers no exemption.
4. **Exclude Relay per-app alone**, which always restores the connection and
   always costs the VPN.

### Getting the connection *and* the VPN (2.8.6, ADR-0010)

Excluding Relay is what makes the connection possible and what takes the VPN
away, and those are the same act — so the only way to have both is to put the
traffic back into the tunnel by another door.

Nearly every client this product's users run — v2ray, sing-box, Hiddify,
Oblivion — exposes a **local SOCKS5 port**, because that is how they serve apps
they are not routing. Relay can forward through it:

> **Advanced → Send the PC's traffic through a proxy** → `127.0.0.1:10808`
> (whatever port the VPN client reports)

**Relay looks for the port itself.** Opening Advanced with the field empty
starts a scan of the loopback ports these clients are found on, and a port that
answers is offered on the line under the field — one tap to use it. It is
offered rather than filled in: this is the only setting in Relay that changes
where packets go, and opening a section is not consent to reroute a connection.

The scan asks each port for **UDP ASSOCIATE**, not just a greeting. A proxy that
grants `CONNECT` and refuses datagrams would pass a politer check and then break
every DNS lookup the PC makes — so nothing would work at all while the setting
read correctly. Tor's SocksPort is exactly that proxy, and it is in the
candidate list so the scan has to meet one and turn it down.

Nothing leaves the phone: every probe is a loopback connection to another
process on the same device.

**If nothing answers, the port has to come from the VPN app's own settings** —
and it is rarely the one you would guess. `10808` is v2ray's default; Oblivion's
turned out to be `1819`, and it also listens on `1820`, which never answered a
SOCKS5 greeting at all. From a computer with adb you can list what is listening:

```
adb shell cat /proc/net/tcp | awk '$4=="0A" {print $2, "uid="$8}'
```

Field 2 is `hex-address:hex-port` — `0100007F:071B` is `127.0.0.1:1819` — and
`uid` matches the VPN app's own (`adb shell dumpsys package <its.package> | grep userId`).
More than one port is normal; only one of them is usually SOCKS5. **The app
cannot do this for you:** Android 10 stopped showing an app any socket but its
own, which is why Relay knocks on ports instead of reading that file.

### Excluding Relay cannot be automated

It gets asked, and the answer is no — not "not yet".

The per-app exclusion list belongs to **the VPN app**, which passes it to
`VpnService.Builder.addDisallowedApplication` when it builds its tunnel. There
is no public API for another app to read or change another app's list, and the
settings key that would hold it is closed: `always_on_vpn_lockdown_whitelist`
throws `SecurityException` for any modern `targetSdk` — see the section below,
where the one neighbouring key that *is* readable is described.

So Relay does the three things it can: it detects the condition from real
evidence rather than guessing, it names the setting and puts the best
arrangement first, and it finds the proxy port for you. The tap in the VPN app
is yours, and after it, **turn the VPN off and on** — Android reads that list
when the tunnel is built, so changing it does nothing until the tunnel is
rebuilt.

With Relay excluded from the tunnel *and* pointed at that port:

- Relay is outside the tunnel, so its replies reach the LAN and the PC connects;
- what it forwards goes in through the proxy, so the PC gets the VPN.

Empty is the default and means the phone's own route, which is what every
release before 2.8.6 did.

**It carries UDP.** The SOCKS5 client is hand-written for exactly that reason:
`golang.org/x/net/proxy` speaks only TCP, and a proxy mode that silently dropped
datagrams would turn Relay into a browser tunnel with nothing on screen to say
so. RFC 1928's UDP ASSOCIATE is implemented beside CONNECT. Oblivion grants it —
measured on 2026-09-16 — and DNS to `1.1.1.1` and `8.8.8.8` answered through the
tunnel; `docs/testing.md` has the numbers.

**It does not help a VPN with no local port and no LAN exemption.** There, the
choice between the connection and the VPN still stands. Relay can describe the
arrangements that exist; it cannot add a port to somebody else's app.

Relay's own copy said "the VPN keeps running — Relay shares it" under option 3
until 2.8.6. It does keep running, for everything except the PC.

### Relay reads the lockdown switch, and only that one

`Settings.Secure.always_on_vpn_lockdown` is annotated `@Readable` in AOSP with no
`maxTargetSdk`, so any app may read it at any API level. Its two neighbours are
not — `always_on_vpn_app` and `always_on_vpn_lockdown_whitelist` both throw
`SecurityException` for a modern `targetSdk`. The platform deliberately left one
boolean open and closed the rest, and that boolean is exactly the switch the user
sees: `Vpn.saveAlwaysOnPackage` writes it as `mAlwaysOn && mLockdown`.

So Relay can say *that* a setting is blocking it. It **cannot** say which VPN app
— that is the closed key — and it cannot deep-link to the page the switch is on,
because AOSP's Settings exports only `Settings$VpnSettingsActivity`
(`Settings.ACTION_VPN_SETTINGS`, API 24) and not `AppManagementFragment`. The
button lands on the VPN list and the message names the last tap itself.

Three rules hold this honest:

- **It is not the signal.** `HandshakeWatch` is — a PC took the settings and no
  handshake came back. A plain full-tunnel VPN with no always-on configured
  causes the identical fault while this setting reads `off`, so a clear reading
  never means "nothing is wrong".
- **The reading is three-valued.** `ON`, `OFF`, `UNKNOWN`. The key is `@hide` and
  could be closed like its neighbours; `UNKNOWN` takes the same path as `OFF`,
  because telling somebody to turn off a switch that may not be on sends them
  looking for something that is not there.
- **It is in every diagnostic report's header**, on every start, whether or not
  anything went wrong. Asking a user to go and look at that screen and report
  back is exactly the round trip this is meant to remove.

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
