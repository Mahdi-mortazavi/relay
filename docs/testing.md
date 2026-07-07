# Testing

## Automated (runs in CI — the only build system)

- **Unit tests** for all core logic: QR payload encode/decode/validation (against [`/shared/test-vectors.json`](../shared/test-vectors.json)), connection state-machine transitions (against [`/shared/connection-states.json`](../shared/connection-states.json)), and — from Phase 1 — proxy snapshot/rollback with a mocked OS layer.
- Android tests run via Gradle on `ubuntu-latest`; Windows tests via `dotnet test` on `windows-latest`. Both are required checks on every PR.
- Coverage target: meaningful coverage of core logic; no vanity percentage on UI glue.

## Manual test matrix (real hardware, CI artifacts only)

There is no local dev loop: manual verification always uses an **installable artifact from a CI run or Release** (APK / Windows installer). Each phase adds its acceptance criteria here with a step-by-step check.

### Setup for the Phase 1 matrix

1. From the Phase 1 CI run (or a release), install the **arm64-v8a APK** on an Android phone and the **x64 installer** on a Windows laptop.
2. On the phone, turn on the Wi-Fi hotspot. Connect the laptop to that hotspot's Wi-Fi.
3. (Optional but representative) turn on any VPN app on the phone.

### Phase 1 acceptance

| AC | How to verify | Pass condition |
|---|---|---|
| **AC1.1** Time-to-connect < 30 s | Fresh install both sides. Start sharing on phone → Scan QR on laptop. Stopwatch from tapping **Start Sharing** to the laptop popup showing **Connected**. | ≤ 30 s, first attempt |
| **AC1.2** Screen-off survival 10 min | While connected and loading a page/stream on the laptop, turn the phone screen off. After 10 min, refresh on the laptop. | Data still flows; state still **Connected** (WakeLock + battery-exemption + foreground service) |
| **AC1.3** Symmetric disconnect < 5 s | (a) Tap **Stop** on phone → laptop shows disconnected within 5 s. (b) Repeat, this time **Disconnect** on laptop → phone returns to waiting within 5 s. | No silent half-open on either side |
| **AC1.4** Clean rollback | Before connecting, note Windows proxy state (Settings → Network → Proxy). Connect, then Disconnect. Re-check. | Proxy values identical to the pre-connect snapshot (the app also asserts this by read-back; failure surfaces `ERR_ROLLBACK_INCOMPLETE`) |
| **AC1.5** Core unit tests pass in CI | Open the Phase 1 CI run. | Both **Android** and **Windows** jobs green; QR/typed-code round-trips and state-machine tests included |
| **AC1.6** CI artifacts installable | From the CI run's artifacts (and the eventual Release), download and install both. | APK installs on arm64-v8a; Windows installer runs (SmartScreen "Run anyway" expected — unsigned) |

### Cross-checks worth doing

- **Typed-code path:** on the laptop choose **Enter Code Manually**, type the 8-char code shown under the QR. Same connect result as scanning.
- **Camera denied:** deny camera to desktop apps, tap **Scan QR** → the app shows the `ERR_CAMERA_DENIED` message pointing to manual entry, never a crash.
- **Phone not on hotspot:** try to connect with the laptop on a different network → `ERR_HOST_UNREACHABLE`, and the proxy is rolled back (re-check as in AC1.4).
- **Crash recovery:** connect, kill the Windows app from Task Manager, relaunch → the proxy is restored on start (unless you changed it meanwhile, in which case your change is kept).
