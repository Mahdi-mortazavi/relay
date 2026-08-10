# Testing

Three rungs, all provisioned by GitHub. Nothing here needs a maintainer's
hardware, an emulator started by hand, or a device plugged into anything.

| Rung | Where | What it proves | Workflow |
|---|---|---|---|
| Unit | `ubuntu-latest` + `windows-latest` | Pure logic, and the SOCKS5 protocol over real loopback sockets | `ci.yml` |
| Device | Android emulator, provisioned per run | The real APK on a real Android image: the golden journey, the foreground service, real relayed traffic | `e2e.yml` |
| Cross-platform | Emulator + the Windows client's own code | The two platforms' shipping code talking to each other over `adb` | `e2e.yml` |
| Windows app | `windows-latest` | The real installer: install → launch → restore → uninstall | `e2e.yml` |

## Rung 1 — unit tests (`ci.yml`)

- **Core logic:** QR payload encode/decode/validation (against [`/shared/test-vectors.json`](../shared/test-vectors.json)), connection state-machine transitions (against [`/shared/connection-states.json`](../shared/connection-states.json)), typed code, reconnect policy, and proxy snapshot/rollback with a mocked OS layer.
- **The SOCKS5 server itself** (`Socks5ServerTest`) is tested over real loopback sockets — real greetings, real CONNECTs, real byte relaying, real malformed input. Not a mock in sight; it is simply fast enough to run on every PR.
- Android tests run via Gradle on `ubuntu-latest`; Windows tests via `dotnet test` on `windows-latest`. Both are required checks on every PR.
- Coverage target: meaningful coverage of core logic; no vanity percentage on UI glue.

## Rung 2 — the device lab (`e2e.yml` → `android-device`)

GitHub creates the AVD, boots it, installs the real debug APK and drives the
real UI. Two API levels, chosen because Relay behaves differently on them
rather than for matrix size: **30** (no runtime notification permission, no
typed foreground services) and **34** (both).

`GoldenJourneyTest` is the canonical journey, asserted end to end:

1. the app opens on the idle screen,
2. **Start Sharing** is tapped the way a user taps it,
3. the phone reaches `Advertising` and renders a real QR,
4. a client connects **to the advertised address** — which also proves
   `LocalAddress` chose an address that is actually bound and reachable,
5. a real HTTP request is relayed through the real SOCKS5 server,
6. the phone reports the connected device,
7. **Stop** tears down the listener *and the live tunnel*, verified by the
   client's socket reaching EOF and the port refusing new connections.

Alongside it: a port-collision failure injection (the preferred port is
occupied; the phone must fall back and still work) and a UI-follows-service
check.

Every run uploads an artifact with a screenshot of each state, the exact
pairing payload the app issued, a journal of what the test did, and `logcat`.

## Rung 3 — the cross-platform leg (`e2e.yml` → `cross-platform`)

The only test where both platforms' real code meets. The device holds a live
session open (`CrossPlatformSessionTest`) while the host runs
`windows/Relay.E2E.Tests` — built from the shipping `Relay.Core` the Windows
app uses — against it through an `adb forward` tunnel:

- the Windows decoder reads the phone's **actual** QR string, so a drift
  between the two implementations fails CI instead of a user;
- both platforms must agree on when the typed code exists at all;
- real HTTP traffic goes host → phone → a destination the phone dials itself;
- domain destinations are resolved **on the phone** (this is what keeps DNS
  inside the phone's VPN);
- BIND and unknown address types are refused correctly and the proxy keeps
  serving afterwards;
- eight concurrent tunnels, the normal case for a browser.

No production code is modified to make this testable: the rendezvous is two
marker files, and the app under test is the same APK a user would install.

## Rung 4 — the Windows app (`e2e.yml` → `windows-app`)

Exercises the artifact a stranger downloads, on a machine that has never seen
Relay: silent install, installed-layout checks (including `resources.pri`,
whose absence once made the window fail to load), launch and survive, the
`--restore-proxy` entry point the uninstaller depends on, then silent
uninstall with nothing left behind.

Every step reads the real `HKCU` proxy values back and asserts they are
identical to the pre-install snapshot. Relay's most dangerous failure is
leaving a dead SOCKS proxy behind, which breaks every app on the machine, so
that assertion is made after install, while idle, after `--restore-proxy`, and
after uninstall.

### Honest limits of the automated lab

| Not covered | Why | Status |
|---|---|---|
| Physical camera scanning a QR off a real screen | No camera on either runner; `-camera-back none` | **BLOCKED — infrastructure** |
| The WinUI window's own controls (clicking Connect, reading the popover) | No reliable UI automation for unpackaged WinUI 3 on a GitHub runner | **BLOCKED — infrastructure** |
| Full Mode / WireGuard | Not implemented yet (Phase 3); the UI does not offer it | **NOT TESTED** |
| Real Wi-Fi/hotspot radio behaviour, screen-off survival, battery | Emulated networking only | **Manual matrix below** |
| Windows sleep/resume | Not available on a hosted runner | **BLOCKED — infrastructure** |

## Manual test matrix (what the lab cannot reach)

Everything above runs on every PR. What follows is the residue that still needs
a human, a phone and a laptop.

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

## Phase 2 — Hardening & UX polish

### Automated (CI)

- `ReconnectPolicy` on both platforms is asserted equal to `/shared/test-vectors.json → reconnect` (schedule, attempt count, and the ~11 s bound) — this is the CI-verifiable half of **AC2.3**.
- Existing QR/typed-code/state-machine/proxy tests continue to gate every PR.

### AC2.2 — every surfaced error is actionable

Fully enumerated in [`errors.md`](errors.md): each code has a severity and exactly one next action, wired to EN/FA strings on both platforms. Verify by reading that table against the app strings; no raw exception text is ever shown.

### AC2.1 — no crash or stuck state across the manual matrix

Run each row from an installable CI artifact; **pass = the app shows a correct state or an actionable error, never a crash or a frozen/half-open state.**

| # | Scenario | Expected |
|---|---|---|
| M1 | Start sharing with hotspot **off** | Phone shows `HOTSPOT_OFF`; retry works after enabling it |
| M2 | Start sharing with **no VPN** active | `NO_VPN_ACTIVE` banner; sharing still works; banner dismissible |
| M3 | Occupy the SOCKS port with another app, then start | `PORT_IN_USE`, or automatic bind to a fallback/preferred port |
| M4 | Laptop scans a **non-Relay** QR | `ERR_QR_INVALID` locally; no system change |
| M5 | Laptop connects while **not on the hotspot** | `ERR_WRONG_NETWORK`; proxy untouched (verify by read-back) |
| M6 | Deny camera, then Scan QR | `ERR_CAMERA_DENIED` pointing to manual entry; no crash |
| M7 | Windows Firewall blocks the port | `ERR_FIREWALL_BLOCKED`; proxy rolled back |
| M8 | Toggle dark/light OS theme while the popup / app is open | Both apps re-theme; text stays legible over glass/acrylic |
| M9 | Open Advanced on both; change theme (Android), change port (Android), view logs | Settings persist; logs are local-only and clearable |
| M10 | Kill either app mid-session | Android: service stops cleanly; Windows: proxy restored on next launch |

### AC2.3 — brief hotspot drop auto-recovers within the bound

1. Connect and start a download on the laptop.
2. Briefly disable the phone's hotspot (or move out/in of range) for **< 11 s**, then re-enable.
3. **Expected:** both apps show **Reconnecting…** (amber), the Windows proxy stays applied, and the session resumes automatically without re-pairing. Recovery completes within the ~11 s bound (`ReconnectPolicy`).
4. **Now exceed the bound:** keep the hotspot off > 11 s. **Expected:** phone → `HOTSPOT_LOST`, Windows → `ERR_CONNECTION_LOST` with the proxy rolled back (verify by read-back, as AC1.4).
