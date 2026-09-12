# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

Relay is one product shipped as two native apps that each speak their own OS's
design language: an Android app (Kotlin + Jetpack Compose, `android/`) and a
Windows desktop client (.NET 8 + WinUI 3, `windows/`). It is not a web product
and has no web surface. Both ends map the same shared token file to platform
constructs (`shared/design-tokens.json`).

## Users

**Primary user.** A person who has a working internet connection on their
Android phone and a Windows PC that does not, and who wants the PC online now —
at a desk, on their own Wi-Fi, on the phone's hotspot, or over a USB cable. The
author is the first instance of this user: the README states Relay began as his
own problem of "a laptop that had no internet and a phone that did."

**Audience languages.** Bilingual, English-first. The repository's primary
README is English with a Persian companion (`README.fa.md`), and every
user-facing string in both apps exists in English and Persian, enforced by tests
rather than by review (`windows/Relay.App/Strings.cs`, `android/.../values-fa/`).

**Contributors** are a second, explicitly courted audience: `CONTRIBUTING.md`,
the ADR log, `good first issue` labels, and a Linux client named as the single
best place to contribute (issue #74).

*Inferred, not confirmed:* nothing in the repository establishes a market size,
a user count, or a geographic market beyond the author's own location (Iran) and
the fact that Persian is a first-class language of the product.

## Product Purpose

Relay shares an Android phone's internet connection with a Windows PC over an
encrypted WireGuard tunnel, with no root, no account, no server and no telemetry.
Every application on the PC is carried — TCP *and* UDP — so games, calls and
installers work, not only a browser.

Success is the setup completing without the user configuring anything: tap
**Start Sharing** on the phone, click the phone in the list on the PC (or type
two digits), approve it on the phone. The stated standard the project holds
itself to is honesty about its own limits — "I would rather write down a
limitation than let you discover it" (README) — with unproven hardware claims
recorded in `docs/testing.md` rather than omitted.

## Positioning

Three things a neighbouring tool could not truthfully copy without becoming this
product:

- **Local-only by construction, not by policy.** No accounts, no servers, no
  telemetry, no analytics. A change adding a network call to anything but the
  user's own phone requires a written ADR — the rule is enforced in `CLAUDE.md`
  and the ADR log, not just asserted in marketing copy.
- **Honest state.** "Connected" means a real WireGuard handshake completed, not
  that an adapter exists. The connection state machine is pinned in
  `shared/connection-states.json` and both platforms are asserted against it.
- **One shared contract, two platforms.** The wire format, pairing rules, state
  machine and design tokens live in `/shared`, and the Android, Windows and Go
  suites consume the same `shared/test-vectors.json`, so the two apps cannot
  drift silently.

## Operating Context

Two devices the user already owns, in the same place, connected however they
already can see each other: the user's own Wi-Fi, the phone's hotspot, or a USB
cable. There is no discovery server and no pairing server.

The pairing ritual is three steps and is the product's defining workflow:

1. **Phone** — tap *Start Sharing*. The phone advertises enough to be found and
   shows a two-digit pairing code and a QR code.
2. **PC** — a tray popover (380dip, summoned from the notification area) lists
   phones already sharing. Click one, or type the two digits, or scan the QR
   with the webcam.
3. **Phone** — approve the request, which shows the requesting computer's
   address, in the app *and* in the notification shade.

Surrounding surfaces that are part of using it: an Android Quick Settings tile,
a home-screen widget carrying the code, a launcher long-press shortcut, a
first-run setup walkthrough (notifications, battery exemption, tile, widget),
three independently silenceable notification channels, and on Windows a tray
presence, a start-with-Windows switch, live statistics read from the adapter, a
connect notification, and a one-button diagnostic report that uploads nothing.

Distribution is direct: two files per release on GitHub Releases, verified
against a `SHA256SUMS.txt` published in that same release. There is no app store
listing. CI is the build system (ADR-0004) — there is no local build step in the
normal contribution loop.

## Capabilities and Constraints

**Confirmed capabilities.** One WireGuard tunnel carrying TCP and UDP for every
application on the PC; no root on either device; pairing by one click, two-digit
code, or QR, always with on-phone approval; tunnel re-pointing when the phone's
address changes (DHCP renewal, Wi-Fi change, NAT rebinding); tested behaviour
under 5% packet loss, a full outage and a mid-transfer path change; DNS and IPv6
leak protection via Windows Filtering Platform rules that live in a session
Windows tears down when the tunnel process ends, measured at 22 µs per new
connection and nothing per byte; self-update on Windows (installs at the next
moment the tunnel is down, or on close) and offered updates on Android (the
platform forbids silent install for a sideloaded app); twenty-one error codes,
each with a human explanation in `docs/errors.md`; English and Persian
throughout with correct RTL.

**Constraints and known limits, as the project states them.**

- The tunnel is IPv4 only today; IPv6 inside the tunnel is later.
- The tunnel is all-or-nothing; split tunnelling / per-app routing is next.
- One PC per sharing session, by design (ADR-0009). More is not planned.
- If the phone's own VPN captures Relay's UID, that VPN swallows the tunnel's
  traffic. Relay detects and explains this; it cannot fix it from inside the app
  (`docs/vpn-compat.md`).
- The Windows installer is not code-signed yet, so Windows warns on first run.
  Signing is the next priority and is described as a cost-and-paperwork problem.
- Relay cannot turn USB tethering on: it is a system setting Android grants no
  app. Relay notices the cable and opens the right screen; the tap is the user's.
- Android 8+; Windows 10/11 (x64 primary, x86 secondary).
- Relay does not claim USB is *faster* than Wi-Fi. The README states this was
  never measured and that a good 5 GHz link can beat USB 2.0.

**Ruled out on purpose:** accounts, servers, cloud sync, telemetry, analytics, a
system-wide proxy mode (this was Fast Mode; removed by ADR-0009), and requiring
root.

**Terminology.** *Sharing* (the phone's state), *pairing code* (two digits),
*QR payload* (versioned JSON, base64url, `shared/qr-payload.schema.json`),
*approval* (the on-phone consent step), *Full Mode* (the WireGuard tunnel — now
the only mode), *leak protection*, *diagnostic report*. Connection states are
`Idle → Preparing → Advertising(QR) → Connected(n) → Error(reason)`.

**Explicitly undecided.**

- **Commercialization is an OPEN DECISION.** The owner has not decided whether
  Relay will ever be commercialized. As of this record the product is free and
  open source under GPL-3.0 with no paid tier, no pro surface, no upgrade path
  and no licensing plan. No future product or design work may assume a paywall,
  a Pro badge, an upgrade prompt, or an account, and none may be invented here.
- Linux and macOS clients are wanted but not scheduled.

## Brand Commitments

- **Name:** Relay. Owner and sole maintainer: Mahdi (Mohammad Mahdi Mortazavi),
  working solo. GitHub: `Mahdi-mortazavi/relay`. License: GPL-3.0.
- **Neutral framing is binding.** `CONTRIBUTING.md` states Relay is documented
  and coded as a general-purpose, local-only connection-sharing utility — a
  networking tool. No identifier, comment, filename or copy may frame it
  otherwise, and requests violating this are rejected regardless of technical
  quality. This constrains product copy, feature naming and positioning.
- **Voice.** First person, plainly the author's own; specific over
  promotional; states what is *not* claimed and what is not yet proven in the
  same breath as what works. Credits contributors and bug reporters by name.
- **Bilingual, neither language an afterthought.** English-first with a Persian
  companion; both languages appear together in the cover art and the roadmap.
- **Assets:** `docs/assets/` — `icon.svg`, `cover.svg` / `cover-mobile.svg`,
  download buttons in both languages, `android-sharing.png`, `windows-idle.png`,
  `relay-demo.gif`. The launcher icon is themed on Android 13+.
- **Trademark note that must survive:** "WireGuard" is a registered trademark of
  Jason A. Donenfeld.

## Evidence on Hand

- **Real product screenshots** of both platforms in `docs/assets/`, plus a
  twelve-second demo GIF of an actual pairing.
- **A real third-party walkthrough:** KASRA MAX recorded a four-minute Persian
  walkthrough with English subtitles, from install to browsing
  (`docs/assets/relay-demo-kasra.mp4`), and reported three real bugs, one of
  which produced the v2.7.1 fix. This is the only named external user in the
  repository.
- **CI evidence:** every commit installs the real app on Android images API
  30–36 and drives its own UI; the Windows client runs against a live phone over
  `adb`; the real installer is installed, launched and uninstalled; a real
  WireGuard handshake is verified across a real WinTun adapter; the published APK
  is then installed on Android 11–16.
- **A written record of what is *not* proven** in `docs/testing.md`, and a
  decision log in `docs/adr/` (ADR-0001 … ADR-0009).
- **Measured figure:** leak protection costs 22 µs per new connection and
  nothing per byte.

**Absences future work must not fill by invention:** there are no user counts,
no download statistics, no testimonials beyond the one named walkthrough, no
throughput or latency benchmarks published as claims, no pricing, no customers,
and no third-party security audit.

## Product Principles

1. **Never lie about state.** A wrong state on screen outranks a missing
   feature, always. "Connected" is a completed handshake; an unknown reading is
   shown as unknown, never as "off".
2. **Nothing leaves the two devices.** Local-only is an invariant with an ADR
   gate in front of it, not a default that convenience can erode.
3. **Write the limitation down.** What hardware has not proved, what a cable
   does not guarantee, what the app cannot fix from inside — all published, in
   the same document as the feature.
4. **Change the contract first.** `/shared` is the single source of truth for
   wire format, state machine, pairing rules and design tokens; editing one
   platform to match the other is how the two apps drift.
5. **Nothing outlives the process.** Relay changes no system-wide setting
   permanently; a crash cannot strand the machine in a broken state.

## Accessibility & Inclusion

- **Colour is never the sole carrier of state.** The Windows status indicator
  pairs its dot with the word (Idle / Connecting / Connected / Reconnecting)
  precisely so hue does not have to be decoded.
- **Contrast wins over translucency.** Tertiary text alphas were raised on both
  themes specifically to clear the 4.5:1 AA floor at 12sp; the light-mode accent
  was darkened so white labels reach ~5.3:1.
- **True RTL, not translated LTR.** Persian layouts mirror; the Windows popover
  even chooses the bottom-left corner under RTL because that is where the RTL
  notification area lives. Camera preview flow direction is pinned so frames are
  not mirrored (issue #40).
- **Reduced motion is a code path.** The Windows "animation effects" system
  switch turns every animation into an instant state change.
- **Pointer/touch targets** never go below 44px for primary controls.
- **Accessibility and RTL correctness are stated review criteria**, not
  afterthoughts (`CONTRIBUTING.md`).
