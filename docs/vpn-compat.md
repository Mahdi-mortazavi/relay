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

Fast Mode is testable now; Full Mode lands in Phase 3 and its column is filled then.

| VPN app | Fast Mode | Full Mode | Notes |
|---|---|---|---|
| Proton VPN | ⏳ pending hardware | — (Phase 3) | Free tier available for testing; check "permanent kill switch" off |
| Cloudflare WARP (1.1.1.1) | ⏳ pending hardware | — (Phase 3) | Uses its own VpnService; verify LAN to the hotspot isn't blocked |
| Mullvad VPN | ⏳ pending hardware | — (Phase 3) | "Local network sharing" toggle must be **on** for hotspot clients to reach the phone |

Legend: ✅ works · ⚠️ works with a noted setting · ❌ incompatible · ⏳ awaiting a
hardware run from a CI artifact. Update this table from the checklist results;
each ⚠️/❌ must name the exact setting or reason.
