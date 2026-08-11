# Windows popover redesign

What changed in the Windows app's window, and why. Each entry is the problem
that existed, the change, and the reasoning — not a list of new files.

The window is a tray popover: 380dip wide, summoned from the notification area,
dismissed when it loses focus. That form factor is the constraint behind most of
what follows. There is one screen, it is small, and whatever is on it has to be
the whole answer.

**Source of truth:** [`Styles/Tokens.xaml`](../../windows/Relay.App/Styles/Tokens.xaml)
(design system) and [`Services/Motion.cs`](../../windows/Relay.App/Services/Motion.cs)
(motion vocabulary). Components reference tokens; there are no literals left in
`MainWindow.xaml` except geometry that is genuinely one-off.

---

## 1. The window was the wrong size for most of its states

**Before.** Fixed at 380×600, every panel `VerticalAlignment="Center"` inside a
`*`-sized row. The idle screen is a headline and two buttons; it occupied about
a third of the window, floating in the middle with empty glass above and below.
The screenshot the E2E job captures shows it plainly.

**After.** The middle row is `Auto`. `ResizeToContent()` measures the content and
resizes the window between 340 and 640dip, clamped to the display's work area.

**Why.** A popover is supposed to be the size of its content — that is what
separates it from a window. The fixed height also had a real failure mode: at
175% scaling the 600dip window was taller than the work area and got pushed off
the top of the screen, and it is not resizable, so the header became unreachable.
The clamp fixes that as a side effect of doing the right thing generally.

## 2. It opened in the wrong corner on a right-to-left Windows

**Before.** `Root.FlowDirection` was mirrored for RTL locales, so the *content*
was correct — but the window was always positioned bottom-right.

**After.** `PositionWindow` picks the corner from `Root.FlowDirection`: bottom-left
under RTL, bottom-right otherwise.

**Why.** RTL Windows puts the notification area bottom-left. The popover was
appearing in the opposite corner from the icon that summoned it, which breaks the
one thing a popover is supposed to communicate: that it belongs to that icon.

## 3. Two buttons of equal weight for two unequal choices

**Before.** "Scan QR" and "Enter Code Manually" were both full-width 46px pills.
One was accent-filled and one was glass-filled, but they had identical size,
identical shape, and identical position in the stack.

**After.** Scanning is the only accented control on the idle screen. Typing a
code is a `QuietButton` — no fill, no border, secondary label colour — beneath it.

**Why.** Scanning is what almost everyone does; manual entry exists for when the
webcam is missing or refused. Giving them the same visual weight makes the user
decide something they don't have the information to decide. Three button roles
are now defined once in tokens (`PrimaryButton` / `SecondaryButton` /
`QuietButton`) and cannot drift apart, which is what happened when every button
carried its own inline `Background`/`Foreground`/`BorderBrush`.

## 4. State was carried by a coloured dot alone

**Before.** A 10px `Ellipse` in the header corner. Grey, teal, amber or red.

**After.** A pill chip: the dot plus the word — Idle / Connecting / Connected /
Reconnecting.

**Why.** A dot alone requires decoding hue to answer "what is happening", which
is both slower for everyone and unavailable to anyone who cannot separate those
hues. Colour is now redundant reinforcement rather than the sole channel.

## 5. Errors said what went wrong and stopped there

**Before.** One `TextBlock` of prose and a Dismiss button. The only way forward
from any error was to dismiss it and work out for yourself what to press.

**After.** A title that names the problem, a body that says what to do, and a
primary action that does it. `ApplyError` maps each of the ten error codes to its
own triple — `ERR_CAMERA_DENIED` offers "Enter code instead"; `ERR_QR_INVALID`
offers "Scan QR" again; the transport failures offer "Try again";
`ERR_ROLLBACK_INCOMPLETE` offers to retry the disconnect, because that is the one
error where dismissing leaves the system proxy still pointed at a phone that
isn't there.

**Why.** An error surface that only reports is a dead end with a full stop on it.
Splitting name from remedy also means the user reads two short lines under stress
instead of parsing one paragraph.

## 6. Manual code entry made the user do work the app could do

**Before.** Type eight characters, read the Connect button, aim, click. Any
mistake surfaced only after clicking, as a generic invalid-code error.

**After.** `OnCodeChanged` validates on every keystroke and distinguishes three
failures: characters outside the alphabet ("that isn't one of the letters on your
phone"), too short (with the count remaining), and a failed checksum. When a
complete, valid code is typed, it connects — no click.

**Why.** The typed code carries a checksum, so "is this right" is answerable
immediately; making the user commit before finding out was withholding an answer
the app already had. And once the code is provably valid, the click is a step
that exists only to confirm what both parties already know. A bad checksum shakes
the field (`Motion.Reject`) rather than only recolouring the hint.

## 7. There was no motion at all

**Before.** Panels swapped by toggling `Visibility`. States replaced each other
between frames.

**After.** `Services/Motion.cs` owns the vocabulary — springs described by
response and damping ratio, never hand-picked durations, so speed and bounciness
stay related. Panels fade out and rise in; buttons dip on *pointer-down*, not on
click; an invalid code shakes; the connected halo breathes.

**Why.** An instant swap gives no sense of what replaced what, and press feedback
that waits for the click to complete reads as lag however fast the rest is. Every
animation starts from the property's current value, so interrupting one continues
from where it visibly is instead of snapping back.

All of it is off when Windows has animation effects disabled —
`SystemPreferences.AnimationsEnabled`, and each entry point returns the end state
directly. That switch is how a Windows user asks for reduced motion; it is a code
path, not a preference read and ignored.

## 6a. The app ran in light theme with a dark-only palette

**Before.** Nothing declared the app's theme, so it inherited the desktop's.
Found by looking at the E2E screenshot rather than by reading the code: the
Expander chrome is light, and where acrylic cannot composite — a VM, a remote
session, transparency turned off — `DesktopAcrylic` falls back to a colour drawn
from the content's theme, so the window became **light grey with translucent
white text on it**. "Not connected", the tagline and "Advanced" were all close to
invisible.

**After.** `RequestedTheme="Dark"` on the `Application`.

**Why.** The palette is dark-only by choice, and until now the framework was
never told. That left the stock controls drawing light-theme chrome under a dark
design, and it made the acrylic fallback actively hostile on precisely the
machines least able to render the effect. Declaring the theme is not a
workaround; it is stating a fact the rest of the design already assumed.

This one is worth recording as a process note: it was invisible in code review
and obvious in a screenshot. The evidence artifact earned its place.

## 7a. Reduced transparency was ignored

**Before.** The acrylic backdrop was applied unconditionally, and the window's
background was a 25%-alpha gradient over it.

**After.** `ApplyMaterial()` checks `UISettings.AdvancedEffectsEnabled`. With
transparency off, the backdrop is skipped and the scrim is replaced by a solid
surface (`WindowSolidBrush`), darker than the scrim so the existing foreground
pairs keep their contrast. The same path runs if acrylic simply isn't available.

**Why.** A translucent scrim with nothing behind it isn't a subtle effect — it is
a window you can see the desktop through, which is the precise problem someone
turning transparency off is trying to solve. Every glass token needs a solid one.

## 8. Keyboard users could not see where they were

**Before.** `UseSystemFocusVisuals="False"` on the shared button style with
nothing put in its place. Focus was invisible.

**After.** The framework's own focus visual, recoloured to the accent and offset
outside the fill (`FocusVisualPrimaryBrush` + `FocusVisualMargin="-3"`). Escape
backs out of whatever is open, or hides the popover if nothing is; Enter takes
the primary action of the visible panel. The popover focuses a sensible control
when it opens.

**Why.** Suppressing focus visuals for looks is the single most common way an
otherwise good interface becomes unusable without a mouse.

Two deliberate choices here. The ring is the *framework's* visual rather than a
`FocusStates` group inside our template: a template-drawn ring only lights if
`Button` raises that visual state, and keyboard visibility is not worth staking
on that. And the key handler is attached with `handledEventsToo: true`, because
the focused control sees the key first and `TextBox` marks several of them
handled — Escape has to work while the caret is in the code box, which is exactly
where someone is most likely to want out.

## 9. Nothing told you how to aim the camera

**Before.** A 300px preview with nothing drawn on it. No indication of how close
to hold the phone, and — because the preview is dark until a frame arrives — no
indication of whether it was working.

**After.** A 150px accent reticle centred over the preview, and a Cancel button.

**Why.** Framing is the user's job and the app has to say where the frame is.

## 10. Cancel didn't cancel

**Before (of the redesign's own first draft).** The busy panel's Cancel called
`StopScanning()`, which does nothing while a connection is being established. A
control that looks like a way out and isn't is worse than no control.

**After.** It calls `DisconnectAsync()`. `stop` is a legal transition from both
`Preparing` and `Advertising`, and `ConnectAsync` already unwinds a proxy it
applied if the state moves underneath it, so this genuinely aborts the attempt.

**Why.** Anything that can take time needs a way out of it, and the way out has
to actually undo the attempt rather than stop displaying it.

---

## Design system

| | |
|---|---|
| **Colour** | Four label levels, four fill levels, one accent. Two semantic pairs (warning, danger) each with a soft variant for backgrounds. Deliberately dark-only: the window floats over its own scrim on `DesktopAcrylic`, so every foreground pair was chosen against that scrim rather than against a system background that can change underneath it. |
| **Type** | Segoe UI Variable Display for large sizes, Text for small — the same optical-size split as SF Pro Display/Text. Seven sizes; tracking negative on display sizes, zero at body, positive on captions so the smallest text does not clot. |
| **Shape** | Concentric radii: card 16, control 12, sunken 10, pill 24. A child's radius is the parent's minus the padding between them, so curves stay parallel. |
| **Spacing** | 4pt grid, `Space1`–`Space8`. |
| **Targets** | `TargetMin` = 44px, enforced on every button role including the quiet one. |
| **Motion** | Three spring specs — Standard (0.42/1.0), Snappy (0.25/1.0), Gentle (0.55/1.0). Durations are derived from response, never chosen. |

## Glass

One glass layer, and only one: the window's `DesktopAcrylicBackdrop`. The scrim
sits directly on it and nothing inside the window is translucent over anything
else. Nested glass is what turns a material into mud, and over an arbitrary
wallpaper it is also what makes text stop being readable.

## What is verified, and what isn't

WinUI cannot be built or run in this repository's Linux CI containers or on the
development machine used for this work, so **none of the visual claims above were
confirmed by eye locally.**

What *is* machine-checked, per commit:

- `Windows (build + test)` compiles the app and runs the unit suite.
- `Windows app (installer → launch → uninstall)` builds the real Inno Setup
  installer on a Windows runner, installs it, launches the app, screenshots it
  (`e2e-out/windows-launched.png`, uploaded as an artifact), then uninstalls and
  asserts the system proxy was left untouched.
- `CodeQL (C#)` analyses the same build.

The screenshot artifact is the visual evidence for this redesign. It shows the
idle state only — the states behind a camera or a live phone are not reachable
from an unattended runner.

## Not done

- **Light theme.** The palette is dark-only by choice (see above) and the app now
  declares that (§6a), so a Windows user in light mode gets a deliberately dark
  popover rather than an accidentally broken one. Making it genuinely
  theme-following still means a second full palette validated against a light
  scrim, and that has not been done.
- **High contrast.** The animation and transparency switches are honoured (§7,
  §7a). The high-contrast themes are not: doing that properly means drawing from
  the system's high-contrast brushes rather than this palette, and the hairlines
  becoming defined borders. Right now a high-contrast user gets the ordinary
  dark window.
- **Narrator.** No `AutomationProperties` pass has been done on the new panels.
