# Changelog

Notable changes to Relay. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
Relay is pre-1.0, so minor versions may still change behaviour.

One tag ships both platforms (`vX.Y.Z`) — see [`docs/release.md`](docs/release.md).
Artifacts for every version are on the
[Releases page](https://github.com/Mahdi-mortazavi/relay/releases).

## [Unreleased]

### Fixed — the August field reports

Five separate defects, reported together and for a while assumed to be one. The
thread that connected them was that nothing in the product could say which build
was in front of anybody.

- **Browsers now actually go through Relay.** The system proxy was written as
  `socks=host:port`. In WinINET's syntax that means SOCKS**4** — Chromium maps a
  bare `socks` scheme to `SCHEME_SOCKS4` — and the phone only ever speaks
  SOCKS5. So Chrome and Edge opened a socket, sent a SOCKS4 handshake, and had
  it dropped, on every page, while Relay's own health probe spoke SOCKS5 by hand
  and went on reporting **Connected**. Users found that entering the same
  proxy by hand in a browser extension worked perfectly and concluded the app was
  broken; it was. The value is now `socks=socks5://host:port`, asserted
  literally by a test so no future edit can quietly walk it back.
- **The Windows QR scanner reads a phone.** Camera frames arrive as BGRA8, four
  bytes per pixel, and were handed to ZXing's three-argument constructor — which
  does not auto-detect anything, and is hard-coded to three-byte RGB24. Every
  frame was consumed at the wrong stride, squeezed by 4/3 with the alpha byte
  folded in as a bright comb; no finder pattern survived, so scanning failed at
  every distance in every build. The decode moved into `Relay.Core` as
  `BgraQrDecoder`, where a test can render a real QR into a real BGRA buffer and
  demand the payload back — which is why this went unnoticed for seven releases:
  it lived in a WinUI project no test assembly can reference. Dark-mode QR codes
  (light-on-dark) now decode too.
- **Exit and Disconnect work from the tray.** Two independent causes, both
  fixed. The tray icon was created free-floating, so its menu had no XamlRoot to
  route clicks through; it now lives in the window's visual tree with an
  explicit `ContextMenuMode`. And every menu command awaited an unbounded
  operation: a Full Mode handshake blocking on a pipe read held the session lock
  that Disconnect and Exit both need, so the menu opened, accepted the click and
  did nothing, forever. Reads now have a deadline, commands have a timeout, and
  Exit has a backstop that no managed code can block. "Only Task Manager could
  close it" is not a sentence that should be true of a tray app.
- **The camera preview is no longer mirrored on a right-to-left Windows.** The
  window sets `FlowDirection` for RTL locales, which mirrors its whole subtree —
  including the `Image` showing camera frames. The preview is pinned to
  left-to-right. (Cosmetic on its own; it is not why scanning failed, and
  treating it as the cause is part of what delayed finding the real one.)
- **A phone sharing in Full Mode says so instead of being called invalid.**
  Full Mode's keys exist only in the QR, so a pairing built from the beacon
  alone was rejected with *"That's not a Relay code"* — in front of a code that
  was entirely correct. New `ERR_FULL_MODE_NEEDS_QR` says to scan the QR.

### Fixed — knowing which build you have

- **The app reports its real version.** Nothing in the .NET build ever stamped
  one, so the diagnostic report — the only place a Windows user ever sees a
  version — said `1.0.0` from v1.0.0 through v1.7.0, while Add/Remove Programs
  and the installer filename both said the truth. Every field report named a
  version that did not exist. The tag is now stamped into the assembly, the
  release job fails if the stamp did not land, and both apps show their version
  in **Advanced**, where a user can read it out.
- **One publishing path.** `android-release.yml` and `windows-release.yml` are
  deleted. The Android one never built the Full Mode library and never passed
  `-PrelayRequireWg`, so it could publish a signed release of an app offering a
  mode that could not start, and it emitted no universal APK; both created
  releases under non-semver tags that GitHub could promote to *Latest*, which
  breaks every `releases/latest/download/…` link in the README at once. This is
  the mechanism behind the report that the universal and arm64 APKs "have
  different code": they were built from different releases and nothing on either
  screen could say so. A tag that is not `v*.*.*` now releases nothing.
- **A tag runs the tests before it ships.** `ci.yml` triggers on branches and
  pull requests, so a tag push matched none of its filters and published without
  a single test having run on that commit. Both release jobs now run their unit
  suites first.
- **The phone only shows two digits when two digits can be found.** The short
  code carries no address; it works only if a PC can hear the phone. On a network
  where neither broadcast nor probe-answer is possible it was still displayed,
  so the PC searched for it forever — and the escape hatch on the PC ("my phone
  shows a longer code") led to a code the phone had never printed. The phone now
  checks, and falls back to the eight-character code, which needs no discovery.
- `repo-maintenance.yml`'s "keep only the newest release" switch is restricted to
  `v*` tags and orders by version rather than by creation date. As written it
  could have kept a stray non-semver release and deleted the one the README
  points at.

### Added

- **Full Mode works on Android and can be switched on.** The phone runs a
  userspace WireGuard endpoint that carries the PC's TCP *and* UDP (ADR-0008).
  Three separate things had to be fixed before it could work at all, and none of
  them had a test:
  - the forwarder library was never put into the APK, so the mode was offered by
    a build that did not contain it;
  - the configuration the app assembled was `wg-quick` INI, while wireguard-go's
    IPC only reads flat hex — every attempt failed as "rejected configuration";
  - the phone routed its peer at `10.7.0.2` while the client and the endpoint
    both use `10.13.37.2`, which would have given a tunnel that handshakes and
    then carries nothing.
  The exact configuration string now lives in `/shared/test-vectors.json`,
  asserted by the Android suite and applied to a real device by the Go suite,
  and the endpoint's own test suite is cross-compiled for Android and run on an
  emulator.
- Full Mode reports its connected peer, so the phone shows **Connected** and
  holds the transfer wake lock while a laptop is actually using the tunnel,
  instead of sitting on "waiting for a PC" through a whole download.
- `scripts/build-wg-aar.sh` builds the forwarder library; CI, the release
  workflow and a developer's machine all use it.

- **A device lab that GitHub provisions itself** (`.github/workflows/e2e.yml`).
  Every pull request now runs the real APK on a real Android emulator (API 30
  and 34), drives the golden journey through the real UI, and relays real HTTP
  traffic through the real SOCKS5 server. A third job runs the Windows client's
  own code against a *live phone* over `adb`, so the two platforms' shipping
  implementations are tested against each other rather than against a fixture. A
  fourth installs, launches and uninstalls the real Windows installer, checking
  the system proxy registry after every stage. Screenshots, the issued pairing
  payload and `logcat` are uploaded from every run.
- `Socks5ServerTest`: 18 protocol-level tests against the real SOCKS5 server
  over real loopback sockets, running on every PR.
- `SECURITY.md`, including an honest threat model for the unauthenticated
  proxy on a shared network.
- Static analysis and dependency review on every PR
  (`.github/workflows/security.yml`), plus Dependabot.
- Issue and pull-request templates.

### Fixed

- **The two apps asked for different codes.** The phone shows a two-digit
  number; the Windows code box was captioned "the 8-character code shown under
  the QR", placeholdered `XXXX-XXXX` and accepted nine characters. The
  two-digit path existed and worked — nothing in the interface pointed at it,
  so holding both screens read as "these two apps do not go together". The box
  is now two characters wide, and the long code moved behind a link named for
  what the user would be looking at ("my phone shows a longer code") rather
  than for its length.
- **Six user-facing strings did not exist**, so the app spoke to people in
  identifiers: someone who typed a code no phone was answering was told
  `CodeNoDevice`, and the error underneath said `ErrCodeNotFound`. They had
  been added to a pair of `.resw` files that nothing reads. Those files are
  deleted — `Strings.cs` is the only store — and a test now derives every key
  the app asks for from the sources and fails if either language is missing
  one.
- **A PC with a default firewall could not find the phone at all.** The
  listener is an unelevated app and Relay's installer is per-user, so Windows
  drops the phone's beacons before Relay sees them: the phone displays a code
  and the PC insists no phone is showing it. The PC now sends a probe and the
  phone answers unicast, so the PC's own outbound state entry opens the return
  path (`/shared/pairing-beacon.md` → The probe). Asserted from both sides
  against `/shared/test-vectors.json`, including on a real device.
- **The phone contradicted itself after the hotspot changed address.**
  Re-advertising dropped the two-digit code, so the screen fell back to the
  eight-character one, and the beacon kept announcing the old address forever —
  a PC that found the code connected to an IP that no longer answered.
- **Full Mode's native library would not have loaded on Android 15's 16 KB-page
  devices.** gomobile aligns for 4 KB pages by default and every emulator image
  uses 4 KB pages, so the app would have installed, offered the mode, and died
  the moment anyone on a new phone turned it on — with nothing in CI to notice.
  The library is now linked with `max-page-size=16384` and the build checks the
  ELF headers rather than trusting the flag.
- The APK no longer carries `com.wireguard.android`'s `GoBackend` natives: a
  second, complete copy of wireguard-go for running a *client* tunnel, which
  this app never constructs. 3.5 MB per ABI, 14 MB off the universal APK.
- **"Stop sharing" did not stop the traffic.** Closing the listening socket and
  cancelling the coroutine scope cannot interrupt a blocking read; only closing
  the socket can. The notification and wake lock went away while the laptop kept
  browsing through the phone.
- **The phone fell back to "waiting for a device" mid-download.** A connection
  that aborted during the handshake, or whose CONNECT failed, decremented the
  per-IP client counter it had never incremented — and a browser produces those
  routinely from the same IP as its live tunnel. The transfer wake lock was
  released with the tunnel still open.
- **Full Mode was selectable but could never start**, so choosing it always
  ended in "Couldn't start Full Mode". It is now shown as not available in this
  build, and a Full setting saved by an earlier version falls back to Fast.
- **The pairing card re-faded and the QR was re-encoded once a second** for the
  whole session, because the state crossfade was keyed on a value carrying live
  byte counters. QR encoding also moved off the main thread.
- **An undismissible notification after any error.** Error paths set the state
  and returned without stopping the foreground service, so the ongoing
  notification survived dismissing the error in the app.
- **UI freeze and ANR risk when starting.** Interface enumeration and up to four
  socket binds ran on the main thread.
- **Content drawn under the system bars** — edge-to-edge was enabled with no
  insets handling.
- **Windows: a lost connection could silently strand the system proxy.** If the
  rollback threw after the reconnect budget was exhausted, the exception
  vanished as an unobserved task fault: the proxy stayed applied, the phone was
  gone, and the UI still said "Connected".
- **Windows: signing out or shutting down while connected left a dead proxy**
  with nothing to undo it. Relay now registers a one-shot recovery entry for
  exactly as long as a session is applied, so the next sign-in repairs it.
- **Windows: uninstalling could delete the proxy backup after failing to restore
  it**, turning a recoverable problem into a permanently broken machine. The
  backup now survives uninstall; only the disposable log is removed.
- **Windows: quitting from the tray after a failed rollback exited silently**,
  leaving the proxy applied and no app to fix it.
- **Windows: a crash-recovery failure at startup could brick every launch**, and
  a flaky VPN or virtual adapter could crash the app on Connect.
- **Windows: the camera preview could crash the app mid-scan** and leaked an
  unmanaged bitmap per dropped frame.
- A leaked file descriptor per failed port bind on Android.

### Changed

- The Windows pairing screen lists the phones announcing themselves right now,
  each with the code it is showing, so the two screens can be checked against
  one another and a single visible phone needs no typing at all. A code typed
  before the phone's first beacon arrives now connects when it lands, instead
  of leaving "no phone has that code" on screen.

- Mode segments are proper radio controls with 48 dp targets.
- `docs/testing.md` describes the automated lab, and states what it cannot
  reach — physical camera scanning, WinUI control automation, Windows sleep — as
  **BLOCKED — infrastructure** rather than omitting it.

---

Earlier history predates this file; see the
[releases](https://github.com/Mahdi-mortazavi/relay/releases) and the commit log.
