# Error taxonomy

Stable error codes shared by both apps. Every user-surfaced error maps to **one concrete next action**. EN/FA message strings live in each app's localization resources keyed by these codes (Android: `strings.xml` / `values-fa/strings.xml`; Windows: `Strings/*/Resources.resw` + the fallback table in `Strings.cs`).

Codes are **never renamed or reused**. Phase 1 introduces the set below; Phase 2 completes the full taxonomy (firewall block, wrong network, VPN-off detection, reconnect exhaustion, …).

## Android (source device)

Surfaced on the phone as `ErrorCode` enum values; the `Error` state carries one.

| Code | Condition | Next action shown to user |
|---|---|---|
| `HOTSPOT_OFF` | No usable hotspot IPv4 interface when starting | Turn on the phone's Wi-Fi hotspot, then try again |
| `PORT_IN_USE` | Every candidate SOCKS port is bound by another app | Close the other app using those ports, then try again |
| `SERVICE_FAILED` | Foreground service stopped unexpectedly | Start sharing again |

## Windows (client device)

Surfaced in the popup. Input-validation codes (bad scan / bad typed code) never touch the system and render locally without entering the shared state machine; the rest arrive through the state machine's `failure` transition.

| Code | Condition | Next action shown to user |
|---|---|---|
| `ERR_QR_INVALID` | Scanned QR isn't a Relay payload (decode/validation failed) | Show the QR from the Relay app on the phone and try again |
| `ERR_QR_NEWER_VERSION` | Payload `v` is newer than this client supports | Update the Windows app |
| `ERR_CODE_INVALID` | Typed code fails length/alphabet/checksum | Re-check the 8 characters on the phone and try again |
| `ERR_HOST_UNREACHABLE` | SOCKS5 probe to the phone failed after applying proxy | Connect this PC to the phone's hotspot Wi-Fi, then try again |
| `ERR_PROXY_APPLY_FAILED` | Applied proxy didn't verify on read-back (rolled back) | Close other proxy/VPN managers and try again |
| `ERR_ROLLBACK_INCOMPLETE` | Disconnect couldn't restore the snapshot (backup kept) | Press Disconnect again to retry the restore |
| `ERR_CAMERA_DENIED` | Camera unavailable or access denied | Allow camera access for desktop apps in Windows Settings → Privacy, or enter the code manually |

## Design rules

- **One next action per message.** No dead-ends, no raw exception text.
- **Actionable, not diagnostic.** The user is told what to *do*, not what failed internally.
- On `ERR_HOST_UNREACHABLE` the client always rolls the proxy back first, so a failed connect never leaves the system half-configured (safety invariant #2).
