# Error taxonomy

Stable error codes shared by both apps. Every user-surfaced error maps to **one concrete next action** (AC2.2). EN/FA message strings live in each app's localization resources keyed by these codes (Android: `strings.xml` / `values-fa/strings.xml`; Windows: `Strings/*/Resources.resw` + the fallback table in `Strings.cs`).

Codes are **never renamed or reused**. This is the complete Phase 2 taxonomy; new codes may be appended in later phases (e.g. WireGuard in Phase 3).

## Severity

- **Error** — the session cannot continue; shown on the `Error` surface with a next action.
- **Warning** — non-blocking; sharing/connecting proceeds, shown as a banner the user can dismiss.
- **Transient** — handled automatically by the bounded reconnect policy ([reconnect.md](../shared/reconnect.md)); only surfaces as an Error if the retry budget is exhausted.

## Android (source device)

| Code | Severity | Condition | Next action shown to user |
|---|---|---|---|
| `HOTSPOT_OFF` | Error | No usable Wi-Fi or hotspot IPv4 interface when starting (works on a shared Wi-Fi/LAN too, not only the phone's hotspot) | Connect the phone to Wi-Fi or turn on its hotspot, then try again |
| `HOTSPOT_LOST` | Transient → Error | Hotspot interface dropped and did not return within the reconnect bound | Check the hotspot is still on, then start sharing again |
| `PORT_IN_USE` | Error | Every candidate SOCKS port is bound by another app | Close the other app using those ports, then try again |
| `SERVICE_FAILED` | Error | Foreground service stopped unexpectedly | Start sharing again |
| `WG_START_FAILED` | Error | Full Mode WireGuard endpoint could not start on the phone | Try Fast Mode, or start sharing again |
| `NO_VPN_ACTIVE` | Warning | No VPN is active on the phone when sharing starts | Informational: you're sharing your regular connection. Turn on your VPN first if you meant to share it |
| `BATTERY_UNRESTRICTED_DENIED` | Warning | Battery-optimization exemption not granted | Allow it so sharing survives screen-off (button opens the exemption dialog) |

## Windows (client device)

Input-validation codes (bad scan / bad typed code) never touch the system and render locally without entering the shared state machine; the rest arrive through the state machine's `failure` transition.

| Code | Severity | Condition | Next action shown to user |
|---|---|---|---|
| `ERR_QR_INVALID` | Error | Scanned QR isn't a Relay payload (decode/validation failed) | Show the QR from the Relay app on the phone and try again |
| `ERR_QR_NEWER_VERSION` | Error | Payload `v` is newer than this client supports | Update the Windows app |
| `ERR_CODE_INVALID` | Error | Typed code fails length/alphabet/checksum | Re-check the 8 characters on the phone and try again |
| `ERR_HOST_UNREACHABLE` | Error | SOCKS5 probe to the phone failed on first connect (proxy rolled back) | Connect this PC to the phone's hotspot Wi-Fi, then try again |
| `ERR_WRONG_NETWORK` | Error | The phone's host IP is not on any connected interface's subnet | This PC isn't on the phone's hotspot. Join the phone's Wi-Fi, then try again |
| `ERR_CONNECTION_LOST` | Transient → Error | An established connection dropped and did not recover within the reconnect bound | The phone became unreachable. Re-check the hotspot and connect again |
| `ERR_FIREWALL_BLOCKED` | Error | Local connect refused/blocked in a way consistent with a firewall rule | Allow Relay through Windows Firewall (or your security software), then try again |
| `ERR_PROXY_APPLY_FAILED` | Error | Applied proxy didn't verify on read-back (rolled back) | Close other proxy/VPN managers and try again |
| `ERR_ROLLBACK_INCOMPLETE` | Error | Disconnect couldn't restore the snapshot (backup kept) | Press Disconnect again to retry the restore |
| `ERR_CAMERA_DENIED` | Error | Camera unavailable or access denied | Allow camera access for desktop apps in Windows Settings → Privacy, or enter the code manually |

## Design rules

- **One next action per message.** No dead-ends, no raw exception text.
- **Actionable, not diagnostic.** The user is told what to *do*, not what failed internally.
- **Transactional safety first.** Any error that occurs after the proxy was applied rolls it back before surfacing, so a failure never leaves the system half-configured (safety invariant #2). The one exception is a *transient* drop inside the reconnect window, where the proxy is intentionally held (see [ADR-0007](adr/0007-bounded-auto-reconnect.md)).
- **Transient handling is invisible until it fails.** The reconnect policy runs silently; the user only sees `HOTSPOT_LOST` / `ERR_CONNECTION_LOST` if the bounded budget is exhausted.
