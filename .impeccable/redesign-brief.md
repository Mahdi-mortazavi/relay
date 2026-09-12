# Relay — UI/UX Redesign Brief

Autonomous execution brief for an AI coding agent. Generated 2026-09-13 from a full read of
`android/` and `windows/` against `PRODUCT.md`, `DESIGN.md` and `.impeccable/design.json`.

Every finding below was verified in source. File paths and quoted values are real.

---

## 0. Mandate

Relay is an **Operate**-mode product: the visitor completes a task. Scanability, honest state,
native platform expectations and the real usage scene outrank expression. Make it feel like a
first-party utility that a stranger trusts on first run — not a themed app.

**This is a refinement, not a replacement.** Keep the product's identity, copy intent, glass
visual world and dark palette. Fix what is broken, wire up what was specified and never built,
and raise craft. Do not invent a new visual language.

---

## 1. Invariants — breaking any of these fails the task

1. **Local-only by construction.** No new network call, endpoint, analytics, telemetry or
   account surface. Any new outbound traffic requires an ADR and is out of scope here.
2. **State honesty.** "Connected" means a completed WireGuard handshake and nothing earlier.
   An unknown reading renders as unknown, never as zero and never as off.
3. **`/shared` is the source of truth.** Token changes land in `shared/design-tokens.json`
   first, then Android and Windows consume them. Never fix a colour in one client only.
4. **Bilingual EN/FA is test-enforced.** Every string you add or change ships in both locales
   in the same commit.
5. **No commercialisation surface.** Commercialisation is an open decision in `PRODUCT.md`.
   Do not add a paywall, Pro badge, upgrade path, account or "unlock" affordance.
6. **Never break functionality.** Build after every phase. A phase is not done until it builds.
7. **Every value is a token.** No new magic numbers in components.

---

## 2. The structural problem

**Relay's design system exists as prose and tokens but was never wired into the Android runtime.**

`DESIGN.md` specifies spring motion described by response and damping ratio, a reduced-motion
code path, a pairing-code type style and a redundant-channel rule. Windows implements all of
them in `Services/Motion.cs`. Android implements none: it runs hand-picked `tween()`s, falls
through to a Material default for the app's single most important number, and carries state in
hue alone.

The Windows defect class is different and consistent: **truth is enforced at the layer that has
tests and lost at the layer the user reads.** `AppController` waits for a real handshake, then
the presentation layer collapses every intermediate state into one unnamed busy screen with copy
describing a feature that was removed.

Fix those two sentences and most of the list below follows.

---

## 3. Phase 0 — Baseline

Before changing anything:

1. Build both clients and record that they pass. Android: the Gradle wrapper in `android/`.
   Windows: the .NET 8 solution in `windows/`.
2. Run the existing test suites, including the locale-parity and shared-contract tests.
3. Capture screenshots of every Android screen in both locales (`en`, `fa`) and both font
   scales (1.0, 1.3), and every Windows panel state. These are your before-set.

If the baseline does not build, stop and report. Do not redesign on top of a broken tree.

---

## 4. Phase 1 — Resolve token truth

**1.1 Accent drift.** `shared/design-tokens.json` says `#45D6B8`. `windows/…/Tokens.xaml`
says `#4ADFBF`, and so do the README badges and all cover art. `ThemeBrush`'s fallback table
in `MainWindow.xaml.cs` hardcodes a third copy: `(0xFF,0x4A,0xDF,0xBF)`, `0xFF7A75`,
`0xF5B95F`, `0x12161D`.
Pick one value — `#4ADFBF` is the one users have actually seen, in every release asset — write
it into `/shared`, propagate to both clients, and delete the private fallback table or generate
it from the shared file at build time.

**1.2 The shared token file is partly stale.** Android raised dark tertiary text from 0.38 to
0.60 alpha and rewrote the light palette (accent `#17A98C` → `#0F7A63`) for AA reasons; `/shared`
still carries the old values, and declares a light theme the Windows client deliberately does
not have. Reconcile: promote the Android AA-corrected values into `/shared`, and mark the light
theme as Android-only rather than shared.

**1.3 Ship the Persian typeface.** `Vazirmatn` appears only as a string in the token file.
There is **no `res/font` directory**, no Windows font asset and no `FontFamily` reference
anywhere — Persian currently renders in whatever face the OS supplies.
`DESIGN.md` records **Estedad** (`aminabedi68/Estedad`, OFL-1.1, variable Arabic+Latin) as the
decided target. Ship it:
- vendor the variable font (and a static fallback) into `android/app/src/main/res/font/` and
  the Windows resources; the licence is OFL — include the licence file;
- set `fontFamily` on every style in `ui/theme/Theme.kt` `glassTypography`, and on the Windows
  text styles;
- re-verify the type scale after the swap — metrics differ from the system face, so line
  heights and tracking need a second look, not a blind carry-over.

---

## 5. Phase 2 — Android motion (the biggest single lift)

Read `references/motion.md` from the impeccable skill and the `apple-design` skill before
writing any animation code.

**2.1 There is no spring anywhere.** Every animation is a duration tween:
`Crossfade(animationSpec = tween(320))`, `animateColorAsState(target, tween(300))`,
`fadeIn(tween(200)) + fadeOut(tween(150))`. Meanwhile `.impeccable/design.json` declares springs
`380/32` and `220/26` "for the Compose side" — unused.
Replace every non-opacity tween with `spring(dampingRatio, stiffness)` derived from the tokens.
Build a single shared motion module first; do not scatter spring literals.

**2.2 `Crossfade` restarts instead of continuing.** When state changes mid-transition it
animates from the target value, producing a visible jump. Animate from the presentation value.
Nothing may lock out input during a transition.

**2.3 No press feedback.** `PrimaryButton` and `SubtleButton` are `Box + .clickable(...)` with
only the default ripple. `DESIGN.md` specifies a dip to `scale 0.97` **on pointer-down**, not on
click. Implement via `interactionSource.collectIsPressedAsState()` →
`animateFloatAsState(if (pressed) 0.97f else 1f, spring(...))`. Response within 100ms.

**2.4 Reduced motion is not a code path.** `StatusDot` and `PreparingPanel` run
`rememberInfiniteTransition(...)` unconditionally. A repo search for `ANIMATOR_DURATION_SCALE`
and `isTouchExplorationEnabled` returns zero hits.
Read `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` into a CompositionLocal; collapse pulses to
a static alpha and every crossfade to an instant cut.

---

## 6. Phase 3 — Android structure and wayfinding

**3.1 Connected looks identical to Advertising.** Both branches render `PairingPanel` with
"Type this on your PC", the giant code and the QR. Once a PC is attached, the first viewport
still asks the user to do a job that is already done.
Build a distinct connected panel: traffic and **Stop** promoted, code and QR demoted behind a
disclosure. Run the wayfinding test on every screen — where am I, where can I go, what's here,
how do I get out.

**3.2 The pairing code uses a style the theme never defines.** `PairingPanel` sets
`style = MaterialTheme.typography.displayLarge`, but `glassTypography` defines only
`displaySmall`, `titleLarge`, `bodyMedium`, `labelSmall`, `headlineMedium`. It falls through to
Material's default (57sp, Regular, zero tracking) — not the specified pairing style
(28/36, Medium, +3sp, tabular). The app's single most important number is unstyled.

**3.3 The update flow has no loading state.** `MainViewModel.updating` exists and is never
collected; `MainActivity` never passes it; `dismissUpdate()` is never wired. Tapping "Get it"
downloads and checksum-verifies an APK with zero on-screen feedback and no way to dismiss.
`ChecksumMismatch` and `Unavailable` go only to `LocalLog` — the user sees nothing.
Wire `updating` into `UpdateBanner`, surface failures, add dismiss.

**3.4 Onboarding cannot be skipped.** The KDoc says "Skippable from the first frame", the
`onboarding_skip` string exists in both locales, and the screen renders only a bottom "Done".
There is no `BackHandler` either. Ship the affordance the string was written for.

**3.5 State carried by hue alone.** `StatusDot` is a bare 10dp `Box` with a colour, no label
and no `contentDescription`. Windows satisfies the redundant-channel rule (dot + word); Android
violates it, and TalkBack users get nothing from the header. Pair the dot with the state word.

**3.6 Off-token surfaces.**
- `res/layout/widget_sharing.xml`: physical `paddingLeft/paddingRight`, hardcoded `#FFFFFFFF`
  and `#B3FFFFFF` (dark-only, ignores the token file), `textSize="34sp" maxLines="1"` with no
  autosizing — clipped at font scale 1.3.
- `AndroidManifest.xml`: `android:theme="@android:style/Theme.Material.NoActionBar"` — a
  platform dark theme, not DayNight and not token-derived, so light-mode users get a dark launch
  window before Compose paints.

**3.7 Two glass bugs worth fixing while you are in there.**
- `GlassComponents.kt`: `Brush.radialGradient(center = Offset(0.5f, 0f), radius = 1200f)` —
  Compose's `center` is in **pixels**, so the accent bloom sits half a pixel from the left edge,
  not the top centre `DESIGN.md` describes. Use a `ShaderBrush` computing
  `Offset(size.width / 2f, 0f)`.
- `glassPanel` uses `linearGradient(Offset.Zero → Offset.Infinite)`, which runs diagonally.
  The spec is light-from-above; `verticalGradient` matches the spec, matches the stroke it is
  paired with, and is direction-neutral under RTL.

---

## 7. Phase 4 — Windows honesty

**4.1 The busy screen describes work the app no longer does.**
`Strings.cs`: `["BusyDetail"] = "Checking the network and applying your proxy settings"`.
ADR-0009 removed Fast Mode; `ConnectFullModeAsync` touches no proxy at all. Meanwhile the real
event of those seconds — a UAC prompt to create the network adapter — is never announced, and
`ERR_WG_ELEVATION_DECLINED` explains it only after the user has already refused.
Rewrite the busy copy to name the two real steps, and warn about the permission prompt
**before** it appears: "Windows will ask for permission to create a network adapter — choose Yes."

**4.2 "Connected but not protected" lives only in a toast.**
`App.xaml.cs` raises one `ShowNotification` when `LeakProtectionUnavailable`. `ConnectedPanel`
carries no warning, and `SyncLeakToggle()` reads the *setting* rather than the *outcome*, so the
switch keeps saying "On". The code's own comment names the problem: the person is told they are
protected while they are not.
Add a persistent warning banner in `ConnectedPanel` — the `ReconnectingBanner` pattern already
exists two lines above — and give the toggle a third reading: "not active this session".

**4.3 The connected stats card can render completely blank.**
`SampleStats()` does `if (reading is null) return;`, and `TunnelStats.Update` returns null
whenever the adapter cannot be read. `StatDownRate`, `StatUpRate`, `StatDownTotal`,
`StatUpTotal` and `StatDuration` have no `Text` in XAML — an unreadable adapter shows an empty
sunken box forever. `StatLatencyUnknown` already proves the right pattern exists, for one field
out of six. Initialise all five to em-dash placeholders.

**4.4 Enter fires the wrong action on the idle screen.** `AttachKeyboard` routes Enter to
`OnScanClick` and `FocusPrimary` falls back to `ScanButton`, but `PopulateIdleList` demotes
`ScanButton` to secondary and accents the phone row. The keyboard default contradicts the
visual primary. When exactly one row exists, focus it and let Enter click it.

**4.5 Press feedback misses the most-clicked control.** `AttachPressFeedback` covers eight
hardcoded buttons and omits every button built in `Fill()` — the phone rows, i.e. the entire
one-click pairing flow — plus `CodeModeLink`, `MinimiseButton`, `CloseButton`,
`AdvancedLogsShare` and `AdvancedLogsClear`. Attach inside `Fill()` and to the rest.

**4.6 State changes are silent to assistive tech.** Repo-wide there are two
`AutomationProperties.SetName` calls and zero `LiveSetting`. The product *is* a state readout.
Add `LiveSetting="Assertive"` to `StatusChipText` and `ErrorTitle`, and raise
`LiveRegionChanged` from `Render`.

**4.7 High contrast is unhandled.** `Tokens.xaml` declares literal `SolidColorBrush` values with
no `ThemeDictionaries`, `App.xaml` pins `RequestedTheme="Dark"`, and `SystemPreferences` reads
`AnimationsEnabled` and `AdvancedEffectsEnabled` but never `AccessibilitySettings.HighContrast`.
Add a `HighContrast` ThemeDictionary mapping to `SystemColor*`, and skip acrylic there the same
way `ApplyMaterial` already does for transparency.

**4.8 Zero-accent screen.** With two or more phones, `Fill()` gives every row `SecondaryButton`
*and* `PopulateIdleList` demotes `ScanButton` — nothing on screen carries the accent. Decide
what the primary action is in that state and give it the accent.

**4.9 Literals bypassing tokens.** `MainWindow.xaml`: `Background="#14171D"`, `CornerRadius="15"`
(no concentric derivation from card-16), `RectangleGeometry Rect="0,0,340,250"`, `Height="252"`,
`MinHeight="212"`, `FontSize="11"`, and `Padding="0,0,12,0"` restated as `const int ScrollGutter`.

**4.10 NIC walk on the UI thread.** `PopulateIdleList` calls `DiscoveryHealth.Diagnose` on every
`DevicesChanged`, enumerating every adapter while the list is empty. The doc comment already
flags this. Move it off the UI thread.

---

## 8. Phase 5 — Persian, RTL and copy

**5.1 Bidi reordering of codes and addresses.** LTR data set in bare text with neutral
punctuation gets reordered by the bidi algorithm under `fa`:
- Android: `typedCode.chunked(4).joinToString("-")`, `"${payload.host}:${payload.port}"` in
  `LabeledValue`, and the `↑ %1$s ↓ %2$s` traffic line.
- Windows: `ConnectedDetailText` (`$"{payload.Host}:{payload.Port} · …"`) and the stat lines
  lack the `FlowDirection="LeftToRight"` pin that `AdvancedVersionValue` already has.
Wrap with `BidiFormatter.unicodeWrap` / an LTR layout-direction scope on Android, and pin
`FlowDirection` on Windows. A `contentDescription` addresses TalkBack, not visual order.

**5.2 The traffic figure is untranslatable.** `HomeScreen.kt` `formatBytes()` does
`String.format(Locale.US, "%.1f GB", …)` with units hardcoded in Kotlin. Move units to
`strings.xml` as parameterised strings and format with the composition locale.

**5.3 Physical-direction glyphs.** `AdvancedSection` renders
`(if (expanded) "▾  " else "▸  ")` — the collapsed caret points right in an RTL layout, and a
Unicode glyph is standing in for an icon. Use a mirroring vector.

**5.4 Dead and weak copy.**
- `error_wg_start_body` still says "Try Fast Mode, or start sharing again." Fast Mode was
  removed by ADR-0009. The error instructs the user to do something that no longer exists.
  **This is a blocker.** Rewrite in both locales to name the real recovery.
- `error_service_failed_*` ("Something went wrong" / "Sharing stopped unexpectedly. Try starting
  it again.") is the only error string that fails the bar the other seven meet. Name the cause.
- Dead strings defined in both locales and referenced nowhere: `scan_hint`,
  `battery_banner_done`, `onboarding_skip` (the last one becomes live in 3.4).

**5.5 Touch-target spacing.** "Share report" and "Clear" in the log header are two adjacent 48dp
targets with no separator. Android's guidance is ≥8dp between targets.

---

## 9. Phase 6 — Verify

A phase is not finished until it is verified. Do this in **bounded passes**, not a loop: build
fully, inspect once with a batched round, fix everything it shows in one batch, confirm with at
most one more round, then stop.

1. **Build both clients.** Android debug and release; the Windows solution.
2. **Run the suites**, including locale parity and the shared-contract tests that Android,
   Windows and the Go tests all assert against the same vectors.
3. **Screenshot matrix on Android**, compared against the Phase 0 before-set:
   `en` and `fa` × font scale 1.0 and 1.3 × light and dark × reduced-motion on and off.
   Nothing clipped, nothing reordered, nothing in the system font.
4. **On a real device.** A Samsung Galaxy A52 (Android 14) is available over adb. Verify by hand:
   press feedback fires on touch-down, an in-flight transition can be grabbed and reversed, and
   the connected screen never appears before the handshake completes.
5. **Windows**: keyboard-only pass (tab order, Enter, Esc), screen-reader pass on the state
   changes, high-contrast pass, and the four failure states — no phone found, phone declined,
   handshake timeout, adapter install refused.
6. **Token audit**: no hardcoded colour, radius or duration left in either client that does not
   resolve through `/shared`.

---

## 10. Definition of done

- Both clients build and all tests pass.
- Every blocker in sections 4–8 is closed: dead Fast Mode copy, missing reduced-motion path,
  unshipped Persian font, the busy-screen lie, the protection warning, and the blank stats card.
- Android motion is springs from a single shared module, interruptible, with pointer-down
  feedback.
- Connected and Advertising are visually distinct on both clients.
- Every screen has designed empty, loading and error states, and every error names both what
  went wrong and what to do about it.
- Persian ships in a real font, and no code, address or figure reorders under RTL.
- `DESIGN.md` and `.impeccable/design.json` are updated to match what shipped — if the code and
  the design file disagree at the end, the work is not done.

---

## 11. Do not

- Do not add any network call, account, analytics or commercialisation surface.
- Do not restyle toward a new visual language. The glass world and dark palette stay.
- Do not "fix" a token in one client. `/shared` first, always.
- Do not add a string in one locale.
- Do not claim a state the state machine has not reached.
- Do not leave a magic number where a token belongs.
- Do not run an open-ended self-QA loop. Bounded passes, then stop and report.
