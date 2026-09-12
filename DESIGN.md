---
name: Relay
description: Liquid Glass — a dark-first, near-monochrome material with one teal accent, shared by an Android app and a Windows tray popover.
colors:
  bg-void: "#0A0C10"
  bg-gradient-top: "#12151C"
  bg-gradient-bottom: "#08090C"
  glass-fill: "rgba(255,255,255,0.06)"
  glass-fill-raised: "rgba(255,255,255,0.09)"
  glass-stroke: "rgba(255,255,255,0.12)"
  glass-stroke-highlight: "rgba(255,255,255,0.22)"
  text-primary: "rgba(255,255,255,0.96)"
  text-secondary: "rgba(255,255,255,0.62)"
  text-tertiary: "rgba(255,255,255,0.60)"
  accent: "#4ADFBF"
  accent-pressed: "#38C2A3"
  accent-subtle: "rgba(74,223,191,0.16)"
  on-accent: "#0A0C10"
  error: "#E5645F"
  error-subtle: "rgba(229,100,95,0.16)"
  warning: "#E0A458"
  accent-brand: "#4ADFBF"   # resolved: now the same value as accent
  light-bg-base: "#EEF1F5"
  light-bg-gradient-top: "#F7F9FC"
  light-bg-gradient-bottom: "#E4E8EE"
  light-glass-fill: "rgba(255,255,255,0.55)"
  light-glass-fill-raised: "rgba(255,255,255,0.72)"
  light-glass-stroke: "rgba(0,0,0,0.08)"
  light-glass-stroke-highlight: "rgba(255,255,255,0.85)"
  light-text-primary: "rgba(12,14,18,0.94)"
  light-text-secondary: "rgba(12,14,18,0.58)"
  light-text-tertiary: "rgba(12,14,18,0.62)"
  light-accent: "#0F7A63"
  light-accent-pressed: "#0B5F4D"
  light-on-accent: "#FFFFFF"
  light-error: "#C7433E"
  light-warning: "#B37417"
  win-scrim-top: "rgba(16,19,24,0.25)"
  win-scrim-bottom: "rgba(8,10,14,0.35)"
  win-window-solid: "#12161D"
  win-label-secondary: "rgba(255,255,255,0.72)"
  win-label-tertiary: "rgba(255,255,255,0.54)"
  win-label-quaternary: "rgba(255,255,255,0.36)"
  win-fill-primary: "rgba(255,255,255,0.12)"
  win-fill-secondary: "rgba(255,255,255,0.08)"
  win-fill-tertiary: "rgba(255,255,255,0.05)"
  win-fill-sunken: "rgba(0,0,0,0.15)"
  win-hairline: "rgba(255,255,255,0.18)"
  win-overlay-hover: "rgba(255,255,255,0.12)"
  win-overlay-pressed: "rgba(0,0,0,0.15)"
  win-focus-ring: "rgba(74,223,191,0.80)"
  win-on-accent: "#05201B"
  win-warning: "#F5B95F"
  win-danger: "#FF7A75"
typography:
  display:
    fontFamily: "system default (Android), Inter fallback"
    fontSize: "34sp"
    fontWeight: 600
    lineHeight: "40sp"
  title:
    fontFamily: "system default (Android), Inter fallback"
    fontSize: "22sp"
    fontWeight: 600
    lineHeight: "28sp"
  body:
    fontFamily: "system default (Android), Inter fallback"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: "22sp"
  caption:
    fontFamily: "system default (Android), Inter fallback"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "16sp"
  pairing-code:
    fontFamily: "system default (Android), Inter fallback"
    fontSize: "28sp"
    fontWeight: 500
    lineHeight: "36sp"
    letterSpacing: "3sp"
  win-title:
    fontFamily: "Segoe UI Variable Display"
    fontSize: "21px"
    fontWeight: 600
    letterSpacing: "-0.015em"
  win-headline:
    fontFamily: "Segoe UI Variable Display"
    fontSize: "17px"
    fontWeight: 600
    letterSpacing: "-0.008em"
  win-body:
    fontFamily: "Segoe UI Variable Text"
    fontSize: "14.5px"
    fontWeight: 400
    lineHeight: "21px"
  win-callout:
    fontFamily: "Segoe UI Variable Text"
    fontSize: "13.5px"
    fontWeight: 400
  win-footnote:
    fontFamily: "Segoe UI Variable Text"
    fontSize: "12.5px"
    fontWeight: 400
    lineHeight: "18px"
  win-caption:
    fontFamily: "Segoe UI Variable Text"
    fontSize: "11.5px"
    fontWeight: 400
    letterSpacing: "0.012em"
  win-code:
    fontFamily: "Cascadia Mono, Consolas"
    fontSize: "25px"
    fontWeight: 400
    letterSpacing: "0.15em"
rounded:
  xs: "8px"
  sm: "12px"
  md: "16px"
  lg: "24px"
  pill: "999px"
  win-sunken: "10px"
  win-caption-button: "7px"
spacing:
  xxs: "4px"
  xs: "8px"
  sm: "12px"
  md: "16px"
  lg: "24px"
  xl: "32px"
  xxl: "48px"
components:
  button-primary:
    backgroundColor: "{colors.accent-brand}"
    textColor: "{colors.win-on-accent}"
    typography: "{typography.win-body}"
    rounded: "{rounded.lg}"
    height: "46px"
    width: "100%"
  button-primary-hover:
    backgroundColor: "{colors.win-overlay-hover}"
  button-primary-pressed:
    backgroundColor: "{colors.win-overlay-pressed}"
  button-secondary:
    backgroundColor: "{colors.win-fill-primary}"
    textColor: "{colors.text-primary}"
    typography: "{typography.win-body}"
    rounded: "{rounded.lg}"
    height: "46px"
    width: "100%"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.win-label-secondary}"
    typography: "{typography.win-callout}"
    rounded: "{rounded.lg}"
    height: "44px"
  button-caption:
    backgroundColor: "transparent"
    textColor: "{colors.win-label-tertiary}"
    rounded: "{rounded.win-caption-button}"
    width: "32px"
    height: "28px"
    padding: "0"
  status-chip:
    backgroundColor: "{colors.win-fill-tertiary}"
    textColor: "{colors.win-label-quaternary}"
    typography: "{typography.win-caption}"
    rounded: "{rounded.lg}"
    padding: "5px 12px 5px 10px"
  code-field:
    backgroundColor: "{colors.win-fill-secondary}"
    textColor: "{colors.text-primary}"
    typography: "{typography.win-code}"
    rounded: "{rounded.sm}"
    height: "64px"
  glass-panel:
    backgroundColor: "{colors.glass-fill}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.lg}"
  glass-panel-raised:
    backgroundColor: "{colors.glass-fill-raised}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.lg}"
  card:
    backgroundColor: "{colors.win-fill-tertiary}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.md}"
    padding: "16px"
---

# Design System: Relay

## Overview

**Creative North Star: "Liquid Glass"**

Relay's interface is a single translucent pane held over a deep, near-black
field, with exactly one colour alive on it. The material does the work that
shadows usually do: depth is communicated by translucency, a light-from-above
gradient fill and a specular top edge, never by a hard drop shadow. The system
is named and documented in `docs/design/README.md`, and its values live in one
place — `shared/design-tokens.json` — which both apps map to platform
constructs rather than forking.

The personality is deferent and quiet. State is the content: the whole interface
exists to say idle, connecting, connected, or what went wrong, and everything
else recedes behind that. Density is generous rather than packed — a 4pt grid,
large touch targets, and a Windows window that resizes to whatever its current
state actually needs instead of holding a fixed height with a hole in it.
Restraint is enforced at token level: one accent, few weights, no nested glass.

The two implementations speak their own platform's dialect of the same material.
Android (Jetpack Compose) carries a full dark *and* light glass set and follows
the system theme or a user override. Windows (WinUI 3) is a 380dip tray popover
whose DesktopAcrylic backdrop *is* the glass layer — the only one in the app —
and is deliberately dark-only, because it floats over an arbitrary wallpaper and
must read as one consistent object regardless of the desktop theme.

**Key Characteristics:**

- Dark-first, near-monochrome; colour is carried almost entirely by alpha over
  white or black.
- One teal accent, reserved for the single most important interactive thing on
  screen.
- Depth from translucency and a 1px gradient stroke; no drop shadows anywhere.
- 4pt spacing grid; concentric radii (a child's radius is the parent's minus the
  padding between them).
- Spring motion described by response and damping ratio, never hand-picked
  durations.
- Contrast always beats the glass effect — several token alphas exist at their
  current values *because* the prettier value failed AA.

## Colors

A near-monochrome field of white-on-black alphas, interrupted by exactly one
saturated teal and two status hues.

### Primary

- **Relay Teal** (`accent`): the one accent. It marks the single most important
  interactive thing on a screen — the primary button, the scanning reticle, the
  focus ring, the connected halo — and a faint radial bloom of it at 5% alpha
  sits behind the Android background gradient. If two things on a screen are
  accented, one of them is wrong.
- **Relay Teal, Pressed** (`accent-pressed`): the pressed state of the accent.
- **Relay Teal, Whisper** (`accent-subtle`, 16% alpha): accent-tinted fills
  behind accent text; never a large area.
- **Signal Teal** (`accent-brand`): the identity colour used across the README
  badges, cover art and download buttons. **As of 2.8.5 this is the same value
  as `accent`** — the name survives because the brand assets refer to it, not
  because it is a second colour.

### Tertiary

- **Amber Caution** (`warning`, Windows `win-warning`): a warning that is not a
  failure — the "connected but unprotected" class of message.
- **Muted Coral** (`error`, Windows `win-danger`): failure states and
  destructive text actions. Paired with a 16% fill (`error-subtle`) when a whole
  surface has to carry the error rather than a line of text.

### Neutral

- **Void** (`bg-void`, `#0A0C10`): the Android base background, and the Android
  launcher icon's background. The darkest surface in the system.
- **Void Gradient** (`bg-gradient-top` → `bg-gradient-bottom`): the vertical
  backdrop the glass refracts, lighter at the top because the light comes from
  above.
- **Glass Fill / Glass Fill Raised** (6% / 9% white): the two elevations of the
  material. Raised is used for the surface that is currently the subject.
- **Glass Stroke / Stroke Highlight** (12% / 22% white): the 1px border, drawn as
  a vertical gradient from highlight at the top to plain stroke below, which is
  what reads as a specular edge.
- **Label ramp** (`text-primary` 96% → `text-secondary` 62% → `text-tertiary`
  60%): three levels of white on Android. Windows runs a four-level ramp of its
  own (96% / 72% / 54% / 36%) tuned against its scrim rather than against a
  system background.
- **Fill ramp** (Windows, 12% / 8% / 5% white plus a 15% black sunken level):
  opaque-enough surfaces drawn *on* the one glass layer.
- **Scrim** (`win-scrim-top` → `win-scrim-bottom`): the gradient the Windows
  popover lays directly on its acrylic so every foreground pair below it has a
  known ground.

### Light theme (Android only)

Android carries a full light glass set: a pale neutral gradient, white fills at
55% / 72%, a black stroke at 8%, and a *darker* accent (`light-accent`) than the
dark theme's. The Windows popover has no light theme by design.

### Named Rules

**The One Accent Rule.** One accent per screen. If two elements on a screen are
accented, one of them is wrong. Everything else is a level of the label ramp or
the fill ramp.

**The No Nested Glass Rule.** There is exactly one glass layer. Nothing drawn on
it is translucent over anything else. Nested glass turns a material into mud, and
on Windows it also makes the popover unreadable over an arbitrary wallpaper.

**The Contrast-Wins Rule.** Legibility beats translucency, every time. Android's
dark tertiary text sits at 60% alpha (not the 38% the token file was originally
written with) because 38% composited to ~3.4:1; light tertiary sits at 62%
(~5.0:1, not 36% / ~2.4:1); and the light accent was darkened to `#0F7A63` so
white labels on it reach ~5.3:1 instead of ~3.0:1. These values are the AA floor
made visible — do not "restore" the prettier ones.

**The Redundant-Channel Rule.** Colour is never the only carrier of state. The
Windows status chip pairs its 8px dot with the word, so a glance answers "what is
happening" without decoding hue.

### Token drift, resolved

`shared/design-tokens.json` is declared the single source of truth in `CLAUDE.md`
and `docs/architecture.md`. Three of the four drifts this section used to record
are closed:

- **The accent.** It existed as three values at once — `#45D6B8` in `/shared` and
  in the Compose theme, `#4ADFBF` in `Styles/Tokens.xaml`, and `#4ADFBF` again as
  a private byte tuple inside `MainWindow.ThemeBrush`. So the colour users had
  actually seen for the life of the product was written down in no authoritative
  place. Resolved to **`#4ADFBF`** — what shipped — in `/shared` first, then
  both clients. `accent-pressed` moved by the same delta the old pair used, so
  pressed sits the same distance from rest rather than being re-picked. The
  private fallback table is **deleted**: every key it covered is defined in
  `Tokens.xaml`, so it could only be reached by an app whose resources failed to
  merge, and there it made a broken theme render as a correct one.
- **Tertiary text and the light palette.** Android's AA-corrected values are now
  the shared ones, rather than a local improvement the contract disagreed with.
- **Light mode.** `/shared` now says in the file that the light block is
  **Android-only**. The Windows popover has no light theme by design.

What has *not* been reconciled: Windows still runs its own four-level label ramp
and fill ramp, tuned against its scrim rather than against a system background.
Those stay namespaced `win-` in the frontmatter above, which continues to record
**the values the code actually renders**.

**Nothing test-enforces `/shared` against either client.** The only link is a doc
comment in `ui/theme/Theme.kt`. A token contract test is the obvious next guard —
this drift was found by reading, which is not a mechanism.

## Typography

**Display Font (Windows):** Segoe UI Variable Display, for large sizes
**Body Font (Windows):** Segoe UI Variable Text, for small sizes
**Android:** the platform system face (no custom family is set in
`ui/theme/Theme.kt`); `Inter` is the declared cross-platform fallback
**Label/Mono Font:** Cascadia Mono, falling back to Consolas — pairing codes,
diagnostics and the log

**Character:** system-native and unfussy on both platforms. Windows exploits
Segoe UI Variable's optical sizing the way SF Pro splits Display and Text, with
tracking that tightens as size grows: negative on display sizes, zero at body,
and positive on the smallest text so captions do not clot.

### Persian typeface

**Incumbent (what ships today).** `shared/design-tokens.json` declares
`typography.family.persian: "Vazirmatn"` — but **no font file is bundled and no
code path loads it**. There is no `res/font` directory on Android, no font asset
in the Windows app, and no `FontFamily` in either codebase referencing it.
Persian therefore renders in each platform's own system face today: Segoe UI
Variable on Windows, the Android system default on Android. The Persian text in
the README cover art (`docs/assets/cover.svg`) is likewise drawn in the SVG's
generic stack, not in Vazirmatn.

**>>> DECIDED TARGET — NOT YET IMPLEMENTED <<<**
**Estedad** (`aminabedi68/Estedad`, OFL-1.1, variable, Arabic + Latin) is the
decided target Persian typeface for Relay. It is a decision, not a shipped fact:
until a font file is bundled and a family is wired into both themes, every
Persian string on screen is still the system face. Do not describe Estedad as
the current font, and do not treat the `Vazirmatn` string in the token file as
the target — that string is the stale incumbent declaration and should be
replaced when Estedad lands.

### Hierarchy

Shared scale (`shared/design-tokens.json`, implemented in Compose):

- **Display** (600, 34/40): the one number or headline a screen is about.
- **Title** (600, 22/28): screen and section titles.
- **Body** (400, 15/22): explanatory prose.
- **Caption** (400, 12/16): hints, log lines, secondary labels.
- **Pairing code** (500, 28/36, +3sp tracking, tabular numerals): the two digits
  the PC needs, spaced so they cannot be misread.

Windows runs a finer, popover-scaled ramp of its own (`Styles/Tokens.xaml`):

- **Title** (SemiBold, 21, −15/1000 em): the app name in the header.
- **Headline** (SemiBold, 17, −8/1000 em, centred): the sentence that names the
  current state.
- **Body** (Regular, 14.5, 21 line-height, centred): explanatory prose.
- **Callout** (Regular, 13.5): quiet text actions.
- **Footnote** (Regular, 12.5, 18 line-height): hints beneath a control.
- **Caption** (Regular, 11.5, +12/1000 em): chips and column headers.
- **Code** (Cascadia Mono, 25, +150 character spacing): the typed pairing code.

### Named Rules

**The Optical-Size Rule.** Large text uses the Display cut and small text the
Text cut; tracking goes negative as size grows and positive as it shrinks. Never
set a 12px label in the Display face.

**The Two-Digit Rule.** The manual code field is two characters wide because the
phone shows two digits. A field captioned for eight characters in front of a
phone showing `42` reads as "these two apps do not go together".

## Layout

**Android** is a full-screen Compose surface: a vertical background gradient
(top → base → bottom) with a faint accent radial bloom at the top centre, and
glass panels floating on it.

**Windows** is a tray popover, and its geometry is the system's most specific
constraint: 380dip wide, summoned from the notification area, dismissed on focus
loss. Three rows — header, the one state that is currently true, and Advanced.
The middle row is `Auto` and `ResizeToContent()` measures it, resizing the window
between **340 and 640dip**, clamped to the display's work area. A popover is the
size of its content; a fixed height also broke outright at 175% scaling by
exceeding the work area on a non-resizable window. Everything sits inside a
scroller with a 12px right gutter reserved for WinUI's overlay scrollbar, and the
window padding is `20,16,20,18`.

Corner placement follows flow direction: bottom-left under RTL (where the RTL
notification area lives), bottom-right otherwise. Content mirrors for RTL; the
camera preview's flow direction is pinned LTR because frames are not UI.

**Spacing** is a 4pt grid — 4, 8, 12, 16, 20, 24, 32 on Windows; 4, 8, 12, 16,
24, 32, 48 in the shared scale. Pointer and touch targets never go below **44px**
however small the label; primary buttons are **46px** tall.

## Elevation & Depth

**There are no drop shadows in this system.** Depth is entirely material:
translucency, a light-from-above gradient fill, and a 1px border drawn as a
vertical gradient from `glass-stroke-highlight` at the top to `glass-stroke`
below, which reads as a specular edge catching a single consistent light source.

Two levels exist, and only two: `glass-fill` (6%) at rest and `glass-fill-raised`
(9%) for the surface that is currently the subject. Within a panel, the fill
gradient runs from the fill alpha plus 0.03 down to the fill alpha.

On Windows the acrylic backdrop *is* the glass, and everything above it is an
opaque-enough fill from the four-level fill ramp. Every glass token needs a solid
fallback: with transparency effects turned off there is no backdrop behind the
scrim, and a 25%-alpha gradient over nothing is a window you can see the desktop
through. `win-window-solid` (`#12161D`) is that fallback, deliberately darker
than the scrim so the same foreground pairs keep their contrast.

### Motion (the depth cue that moves)

Windows' motion vocabulary lives in one file (`Services/Motion.cs`) and is
entirely springs, described by **response and damping ratio** — durations are
derived, never hand-picked, so speed and bounciness stay related:

- **Standard** (response 0.42, damping 1.0) — the workhorse.
- **Snappy** (0.25, 1.0) — direct-manipulation feedback that must feel attached
  to the pointer. Buttons dip to `scale 0.97` on *pointer-down*, not on click.
- **Gentle** (0.55, 1.0) — larger surfaces that would feel frantic at standard.

Opacity is a plain ease, never a spring, because springing transparency flickers
past 1.0 and back. Every animation starts from the property's *current* value, so
interrupting one continues from where it visibly is instead of snapping back.

The shared token file additionally declares durations (instant 100, fast 180,
base 260, gentle 420 ms), easings (`cubic-bezier(0.2, 0.8, 0.2, 1)` standard,
`cubic-bezier(0.0, 0.0, 0.2, 1)` decelerate) and springs (default 380/32, settle
220/26) for the Compose side.

### Named Rules

**The Flat-Material Rule.** No `box-shadow`, ever. If something needs to feel
lifted, raise its fill alpha and let the stroke highlight do the rest.

**The One Glass Rule.** One translucent layer per app. Everything on it is
opaque enough to read over an arbitrary backdrop.

**The Reduced-Motion Rule.** The Windows "animation effects" system switch is a
code path, not a preference to read and ignore: with it off, every animation
above becomes an instant state change.

## Shapes

Soft, continuous and concentric. The scale is 8 / 12 / 16 / 24 / pill in the
shared tokens; Windows names the same values by role — window 12, control 12,
card 16, pill 24, sunken 10, caption button 7.

**Borders** are always 1px, and on glass they are a gradient rather than a flat
colour. Cards on Windows use the flat `SeparatorBrush`; interactive surfaces use
the slightly brighter `win-hairline`.

**Clipping** is used sparingly and for one reason: the camera preview is clipped
to a 15px inner radius inside a 16px card so the frame does not square off the
corner it sits in.

### Named Rules

**The Concentricity Rule.** A child's radius is the parent's radius minus the
padding between them, so curves stay parallel instead of drifting. A 16px card
with 4px padding holds a 12px control.

## Components

### Buttons

Three roles, defined once in tokens, and they must not look interchangeable:
the accent one is the thing to do, the plain one is the way out, the quiet one
is a text-weight action that cannot compete with either.

- **Shape:** fully rounded pill (24px radius), full width, 46px tall with a 44px
  minimum target.
- **Primary:** accent fill, `win-on-accent` (`#05201B`) label, no border,
  SemiBold body size. One per screen.
- **Secondary:** `win-fill-primary` fill, primary label, hairline border, Regular
  weight. Visibly recessive.
- **Quiet:** no fill, no border, secondary label colour, callout size. Used for
  "enter a code instead" and destructive text actions.
- **Hover / Pressed:** an overlay layer over the control's own fill —
  `win-overlay-hover` (12% white) and `win-overlay-pressed` (15% black) — plus
  the Snappy 0.97 scale dip on pointer-down.
- **Focus:** the framework's own focus visual, re-coloured to the accent at 80%
  alpha, 2px, pushed 3px *outside* the fill. Drawn by the focus manager rather
  than by the control template, so a keyboard user cannot lose their place if a
  visual state fails to raise.
- **Disabled:** border/background to 35% opacity, content to 50%.
- **Caption buttons** (minimise, close) are the deliberate exception to the 44px
  rule: 32×28, 7px radius, tertiary label, Segoe Fluent Icons. They are
  pointer-only affordances on a pointer-only surface, and sizing them like
  primary actions would make the header shout.

### Status chip (signature component)

The system's most characteristic control. A pill (24px radius, `win-fill-tertiary`
fill, `10,5,12,5` padding) containing an 8px dot and the word — Idle /
Connecting / Connected / Reconnecting — in caption type. It replaced a bare
coloured dot, and the replacement is doctrine: colour reinforces the word, it
never carries the state alone.

### Cards / Containers

- **Corner:** 16px. Inner clipped content steps down to 15px.
- **Background:** a fill-ramp level, never glass-on-glass. The camera preview
  card uses an opaque `#14171D`.
- **Shadow strategy:** none — see Elevation & Depth.
- **Border:** 1px separator; hairline on interactive surfaces.
- **Internal padding:** the 4pt grid, typically 16px.

### Inputs / Fields

- **Style:** sunken rather than raised — `win-fill-secondary` fill, hairline
  border, 12px radius, 64px tall for the code field, centred content.
- **The code field** is 2 characters wide, mono, 25px, +150 character spacing,
  with a numeric input scope.
- **Validation is live:** every keystroke is checked, and three failures are
  distinguished by message (character outside the alphabet, too short with the
  count remaining, failed checksum). A complete valid code connects with no
  click at all.
- **Error:** the field *shakes* (`Motion.Reject`) rather than only recolouring
  the hint, and the hint text says what is wrong with what you typed.

### Navigation

There is none in the conventional sense, and that is the design. The Windows app
has one screen with exactly one state panel visible at a time; panels fade out
and rise in (`EnterPanel`) rather than swapping visibility between frames. The
only persistent chrome is the header (title, status chip, caption buttons) and a
collapsible Advanced section at the bottom.

### Error surface

A title that names the problem, a body that says what to do, and a primary
action that does it — each error code mapped to its own triple. An error surface
that only reports is a dead end with a full stop on it.

## Do's and Don'ts

### Do:

- **Do** change `shared/design-tokens.json` first. Both apps map it to platform
  constructs and both are asserted against `/shared`; editing one platform to
  match the other is how the two apps drift.
- **Do** reference tokens, never literals. `Styles/Tokens.xaml` holds every value
  the Windows UI uses; `MainWindow.xaml` carries no literal except genuinely
  one-off geometry.
- **Do** give exactly one element per screen the accent.
- **Do** pair colour with a word or shape whenever it carries state.
- **Do** keep the concentricity relationship when nesting: child radius = parent
  radius − padding.
- **Do** describe motion as response + damping ratio, and start every animation
  from the property's current value.
- **Do** honour reduced motion as a branch, not a tuning.
- **Do** keep every interactive target at 44px or larger, with 46px as the
  button height.
- **Do** provide a solid fallback for any glass surface, darker than its scrim.
- **Do** design RTL as a mirror, and pin flow direction on anything that is
  content rather than UI (camera frames).

### Don't:

- **Don't** add a `box-shadow`. Elevation is translucency in this system.
- **Don't** nest glass on glass. There is one translucent layer per app.
- **Don't** accent two things on one screen.
- **Don't** lower the tertiary-text alphas or lighten the light-mode accent back
  toward their "prettier" originals — those exact values are what clears 4.5:1.
- **Don't** let translucency win an argument against legibility.
- **Don't** hand-pick an animation duration.
- **Don't** introduce a light theme for the Windows popover; it is dark-only on
  purpose, because it floats over an arbitrary wallpaper.
- **Don't** describe Estedad as the Persian face in use. It is the decided
  target; the system face is what renders today.
- **Don't** add a fourth button role, or give two roles the same visual weight.
- **Don't** introduce any surface that implies an account, a paid tier, or an
  upgrade — commercialization is an open decision and the product is free and
  open source today (see `PRODUCT.md`).
