# UX audit — Relay 2.10.0

**Status:** Phase 1 (audit only). No code was changed to produce this.
**Date:** 2026-10-21 · **Scope:** Android app (`android/`), Windows client (`windows/`), `/shared`.

How this was done: by reading the code paths a person goes through, not by
watching people. Every problem below cites the file it comes from. Where the
code cannot settle a question — how something renders on a real screen, what a
screen reader actually says — it is marked **verify on hardware**, and nothing
here is a measurement of users. There are no user numbers in this document
because the project has none (`PRODUCT.md` → "Absences future work must not
fill by invention").

Times are taken from constants in the code (`LinkWait`, `HandshakeWatch`,
`ClientGate.TIMEOUT_MS`, `handshakeTimeout`) or counted as user actions. They
are not stopwatch figures.

---

## A. Journey maps

"Action" means something the person has to do: a tap, a click, a keystroke, or
answering a system prompt. System prompts are counted separately, because Relay
can explain them but cannot remove them.

### Job 1 — the first-ever connection (both apps just installed, home Wi-Fi)

| # | Where | What they see | What they must understand | What they do | Time cost | Friction |
|---|---|---|---|---|---|---|
| 1 | PC | SmartScreen "Windows protected your PC" (installer is unsigned, `docs/release.md`) | That it's safe to continue | **More info → Run anyway** (2 actions) | — | High. Outside Relay's UI. The roadmap names it the biggest drop-off. |
| 2 | Phone | `OnboardingScreen`: "Keep Relay within reach", five steps — notifications, battery, tile, widget, long-press | Which of the five are needed to share at all. The screen doesn't say: two are essential and three are conveniences, all with the same weight | Tap **Done** (1). Optionally Allow notifications (1 + system dialog) and Allow battery (1 + system dialog) | One screen before sharing exists at all | **Major.** The first thing a new user meets is homework about widgets, before they've seen Relay work once. There's no Skip, though `onboarding_skip` exists in both languages and is unused. |
| 3 | Phone | Home, Idle: "Not sharing", one line of explanation, **Start Sharing**, tagline. A **Keep sharing alive** banner below if battery wasn't granted | Nothing — this is clear | **Start Sharing** (1). On Android 13+, the notification permission dialog if step 2 was skipped (1) | Up to ~8 s of "Starting…" when the link is still coming up (`LinkWait`), with no reason shown | Minor. "Starting…" pulses with no hint of what it's waiting for. |
| 4 | Phone | Waiting: "Waiting for your PC…", the two-digit code (largest thing on screen), **Stop**, then a full-width QR. Possibly a "No VPN is active" amber banner | Read two digits, or point a camera | Nothing yet | — | Minor. The amber dot on "No VPN is active" reads as a problem, though sharing without a VPN is normal. |
| 5 | PC | Popover, Idle: "Ready to connect". If the phone is already announcing, a list row like `42   SM-A307FN   ·   Wi-Fi` as the accented button | That the row *is* the phone | **Click the row** (1) | Discovery is immediate if the beacon has arrived | Good. This is the best moment in the product: one click. |
| 6 | PC | Busy: spinner, "Check your phone", "Your phone is asking whether to let this computer in. Tap Allow." | That the next step is on the other device | — | Up to 60 s before the phone gives up (`ClientGate.TIMEOUT_MS`) | Minor. No countdown, and this PC's name or address isn't shown, so the two screens can't be checked against each other. |
| 7 | Phone | `AlertDialog` (Material default, not glass): "Allow this computer? 192.168.1.23 wants to share your connection." Also a heads-up notification | That the IP is *this* laptop. Nothing shows the laptop's name, though the PC sends it | **Allow** (1) | — | **Major.** See P-04: the laptop's name crosses the wire and is thrown away. |
| 8 | PC | UAC prompt to create the adapter (every connect, `ElevatedTunnelHost`). Busy text warns about it beforehand | That Yes is needed | **Yes** (1) | Blocks until answered. An unanswered prompt looks like a hang (`docs/testing.md`) | Major, but structural (ADR-0009 accepted it). |
| 9 | Both | PC: halo, "Connected via SM-A307FN", address, a stats card. Phone: "1 device connected" plus totals, **with the code and full QR still filling the screen** | That it worked | — | Up to 20 s if the handshake is slow (`handshakeTimeout`) | **Major** on the phone: P-03. |

**Count, happy path (code, one phone found):** phone 3 actions (Done, Start,
Allow), plus 0–2 system dialogs; PC 1 click, plus 1 UAC; plus 2 SmartScreen
clicks once ever. The QR path skips the phone-side Allow, but adds a camera
permission and aiming.

### Job 2 — the daily reconnect (both apps installed and set up)

| # | Where | What they see | Do | Friction |
|---|---|---|---|---|
| 1 | Phone | Quick Settings tile or widget (if added), otherwise the app | Tap tile (1), or open app + **Start Sharing** (2) | Low, if the tile was added. Onboarding is the only place that offers the tile. |
| 2 | PC | Tray icon, then popover | Click tray (1) | Low. "Start with Windows" is off until turned on in Advanced. |
| 3 | PC | Phone row in the list | Click it (1) | Low |
| 4 | Phone | Approval dialog **again** — new keys every session (ADR-0009) | Allow (1) | **Major for a daily task.** Today's design can't remember the laptop, and changing that touches the security model and `/shared` (see C, structural). |
| 5 | PC | UAC **again** | Yes (1) | Major, structural. |
| 6 | Both | Connected | — | — |

**Count:** 4–5 actions plus 1 UAC, every day, for the same two devices.

### Job 3 — something goes wrong

Four representative failures, traced through the code.

| Failure | What the user sees | What they must do | Does the screen get them there? |
|---|---|---|---|
| Phone on mobile data only (`ONLY_MOBILE_DATA`) | Phone: error card, title + body, **Try Again** (primary) + **Dismiss** | Join Wi-Fi, or turn on hotspot or tethering, in Settings | **No.** The only accented action retries, which waits ~8 s again and fails the same way. No button opens Wi-Fi, hotspot or tethering settings, though `MainViewModel.openTetheringSettings` already exists. |
| VPN swallows replies (`PC_GOT_NO_REPLY` / `ERR_WG_NO_HANDSHAKE`) | PC, after 20 s: "The tunnel came up but the phone never answered…", primary **Scan QR**. Phone: amber banner, ~90 words, **Dismiss** | Change a setting in the VPN app, toggle the VPN | Partly. The phone explains, but in one 12 sp paragraph with no structure. The PC's only primary action ("Scan QR") is right for one of its two stated causes. |
| Phone stops answering mid-session (`ERR_CONNECTION_LOST`) | PC: "Connection lost — The phone became unreachable. Re-check the hotspot and connect again." **Try Again** + **Dismiss** | Go back to the phone list and click it | **Misleading.** "Try Again" doesn't retry. Its `default:` branch only dismisses (`MainWindow.xaml.cs:1681`). Both buttons do the same thing, and "the hotspot" is wrong for Wi-Fi and USB users. |
| PC typed a code no phone is showing (`ERR_CODE_NOT_FOUND`) | Never shown as an error while typing. The hint says "Looking for that phone…" until a beacon matches | Check the phone is sharing and on the same network | Good. Live, inline, no dead end. |

### What already works, and must survive Phase 2

- **One-click pairing on Windows**, when the phone is already announcing (`PopulateIdleList`). The scan button even demotes itself to secondary when a phone is listed.
- **Live code validation** on Windows: distinct messages for wrong characters, a leading zero, too short, and ambiguity, plus a shake on rejection and auto-connect on a match.
- **`DiscoveryHealth`**: the empty phone list says *why* it's empty (no network vs. can't listen vs. nothing heard yet).
- **Stop is reachable without scrolling** on a small phone (`PairingPanel` comment), and Windows **Cancel undoes the attempt**, not just the screen.
- **Touch targets on Android** consistently use `minimumInteractiveComponentSize()`.
- **Reduced motion on Windows** is a real code path (`Motion.Enabled`).

---

## B. Prioritised problem list

Severity: **blocker** (stops a job, or makes the UI say something untrue),
**major** (costs real time or trust), **minor** (polish).
Effort: **S** < ½ day, **M** 1–3 days, **L** needs a design or contract change.

### Honesty and state (principle 1: never lie about state)

| ID | Problem | Where | Why it hurts | Sev | Effort | Evidence |
|---|---|---|---|---|---|---|
| P-01 | **"Try Again" on Windows doesn't try again.** For every error with primary `TryAgain`, the handler's `default:` branch dismisses to Idle, and Dismiss does the same. Two buttons, one behaviour, and the accented one promises something it doesn't do. | `MainWindow.xaml.cs:847` (`ApplyError`), `:1681` (`OnErrorPrimaryClick` default) | Breaks "one primary recovery action", and the label is untrue. | **blocker** | S | `"ERR_CONNECTION_LOST" => (…, "TryAgain")` → `default: _controller.DismissError(); … Render();` |
| P-02 | **Unknown error codes render as "Windows refused the proxy change".** The fallback branch and the null-code default both use Fast Mode's proxy copy, a transport removed in ADR-0009. | `MainWindow.xaml.cs:882`, `Render()` `ApplyError(_controller.ErrorCode ?? "ERR_PROXY_APPLY_FAILED")` | Any future or unmapped code tells the user about a system proxy Relay no longer touches. | major | S | `_ => ("ErrTitleProxy", "ErrProxyApply", "TryAgain")` |
| P-03 | **The phone's Connected screen is still the pairing screen.** Connected reuses `PairingPanel`: the two-digit code (displayLarge) and a full-width QR stay the dominant content, and "1 device connected" is a 15 sp line above them. | `HomeScreen.kt:435` (`PairingPanel(…, connected = true)`) | The one-second question "is it working?" is answered by the smallest text on the screen. Showing a pairing code to someone already paired invites a second pairing, which can't succeed (one peer, ADR-0009). | **major** | M | `is ConnectionState.Connected -> PairingPanel(rememberQrText(current.payload), current.typedCode, current.shortCode, …)` |
| P-04 | **The approval prompt shows an IP and never the computer's name, though the contract says it must.** The PC sends `{"pair":1,"name":"MAHDI-LAPTOP"}`. `/shared/pairing-beacon.md` says the name "is shown to the person being asked". Android's `ClientGate.Pending` holds only `address`, and `PairingServer` passes only `address`. | `ClientGate.kt:33`, `PairingServer.kt:134`, `ApprovalNotice.kt`, `HomeScreen.kt:416`; contract: `shared/pairing-beacon.md` → "The pairing exchange" | An address is something a person can't check. It's the moment the security model leans on a human, and it gives them the least to go on. This is **contract drift**: `/shared` already says it, and one platform doesn't do it. | **major** | S–M | `data class Pending(val address: String)`; `windows/Relay.Core/PairingClient.cs:106` sends `name` |
| P-05 | **Windows status chip says "Not connected" in red during an error.** The chip's label falls through to `StatusIdle` for both `Error` and local errors, so the dot says one thing and the word another. | `MainWindow.xaml.cs:713`, `:768` | The redundant-channel rule exists so the word and the colour agree. Here they disagree. | minor | S | `SetStatusChip("DangerBrush", Strings.Get("StatusIdle"))` |
| P-06 | **The 2.10 VPN copy contradicts itself across platforms.** Windows `ErrPhoneRepliesBlocked` still says excluding Relay means "this PC gets the phone's own connection and not its VPN". Since 2.10 that's no longer true when the VPN exposes a proxy (ADR-0011). `docs/errors.md` → `PC_GOT_NO_REPLY` still gives the pre-2.10 action. | `Strings.cs` (`ErrPhoneRepliesBlocked`, both languages), `docs/errors.md` | Users are told to accept a trade they may no longer have to make. My own 2.10 change missed these. | major | S | Compare `values/strings.xml` `warning_no_reply_body` (updated) with `Strings.cs` `ErrPhoneRepliesBlocked` (not) |
| P-07 | **Fast Mode is still alive in the Windows controller.** `AppController.ConnectAsync` keeps the whole SOCKS path (`ProxySession`, `ProbePhone`, `ERR_PROXY_APPLY_FAILED`, `ERR_ROLLBACK_INCOMPLETE`), reachable by any QR whose `mode` is `socks5`. ADR-0009 says it was removed. | `Services/AppController.cs:62–150`, `Relay.Core/QrPayloadCodec.cs:100` | A legacy QR produces a proxy change on this machine and errors that talk about restoring proxy settings. Dead surface is also where wrong copy hides (P-02). | minor | M | `if (payload.Mode != QrPayload.ModeSocks5) { Fail("ERR_QR_NEWER_VERSION") … }` and the SOCKS branch below it |

### Error copy and recovery

| ID | Problem | Where | Sev | Effort | Evidence |
|---|---|---|---|---|---|
| P-08 | **Android errors always offer Try Again first, even when trying again can't work.** `HOTSPOT_OFF`, `ONLY_MOBILE_DATA` and `ONLY_VPN` need a settings change. The primary should open Wi-Fi, hotspot or tethering settings (the tethering intent already exists), and retry should follow automatically when the link appears. | `HomeScreen.kt:580–620` | **major** | M | `PrimaryButton(text = stringResource(R.string.action_retry), onClick = onRetry)` for every code |
| P-09 | **Errors say what to do, but not what Relay already tried.** For example, `LinkWait` waited ~8 s, the reconnect budget ran out, the handshake waited 20 s. Saying so tells the person why retrying unchanged won't help. No error surface has collapsible technical details. The only route to them is Advanced → log. | Both error panels | major | M | `ErrorPanel` (Android) / `ErrorPanel` (Windows) render title + body + buttons only |
| P-10 | **Copy names "the hotspot" when the link may be Wi-Fi or USB.** Android `HOTSPOT_LOST` title "Hotspot connection lost". Windows `ErrConnectionLost` "Re-check the hotspot". "Hotspot address" label in Advanced on both platforms. | `strings.xml` `error_hotspot_lost_*`, `advanced_ip`; `Strings.cs` `ErrConnectionLost`, `AdvancedAddress` | minor | S | Since 2.8 the link is "Wi-Fi, hotspot, or USB" everywhere else in the copy |
| P-11 | **Wrong or missing copy for specific codes.** `ERR_WG_ALREADY_RUNNING` reuses "Close any other VPN" (the cause is Relay's own tunnel). `ERR_QR_NEWER_VERSION` says "update this app" with no button, though the updater exists. Android `SERVICE_FAILED` points to "In Advanced", but the battery control isn't in Advanced. | `MainWindow.xaml.cs:880`; `strings.xml` `error_service_failed_body` | minor | S | — |
| P-12 | **Warnings are long single paragraphs at 12 sp.** `warning_no_reply_body` is ~90 English words with three alternative fixes in prose. On Windows, `ErrPhoneRepliesBlocked` is ~110 words. | `strings.xml`, `Strings.cs` | major | S (copy) / M (layout) | The fixes should be a short numbered list, best one first, with the details collapsible |
| P-13 | **"No VPN is active" is an amber warning every session for everyone who doesn't use a VPN.** It's informational (`docs/errors.md`), but it uses the warning dot and banner shape that real faults use. | `HomeScreen.kt:296` | minor | S | Same banner composable and dot colour as `PC_GOT_NO_REPLY` |
| P-14 | `docs/errors.md` says Windows strings live in `Strings/*/Resources.resw` + a fallback table, which `CLAUDE.md` forbids, and still describes Fast Mode conditions (`ERR_HOST_UNREACHABLE` "SOCKS5 probe", `PORT_IN_USE` "candidate SOCKS port"). | `docs/errors.md` intro and tables | minor | S | — |

### Pairing, information hierarchy, stats

| ID | Problem | Where | Sev | Effort | Evidence |
|---|---|---|---|---|---|
| P-15 | **The PC never shows its own name or address while the phone asks about it.** "Check your phone" can't be compared with what the phone shows (see P-04). | `Strings.cs` `BusyPairingDetail`, `MainWindow.xaml` `BusyPanel` | major | S | — |
| P-16 | **No sense of time while waiting for approval.** The phone gives up at 60 s and the PC's own timeout is 75 s. Neither screen shows that a clock is running, so after a minute "Check your phone" turns into `ERR_PAIRING_DENIED` with nothing in between. | `ClientGate.kt:127`, `PairingClient.cs:56` | minor | S | — |
| P-17 | **Phone stats: totals only, no rate.** "↑ 1.2 MB ↓ 40 MB" in caption-tertiary. Windows shows rates, totals, latency and duration in a 3×2 grid where five of six values share one weight. Neither platform leads with speed in a clear hierarchy. | `HomeScreen.kt:476`; `MainWindow.xaml` stats grid | minor | M | — |
| P-18 | **Windows stats strings are built by concatenation with English units.** `$"{Strings.Get("StatDown")} {TunnelStats.Bytes(...)}"`, units `"KB","MB","GB","TB"`, `"/s"`, `"ms"` hard-coded in Core. In Persian the label is translated and the unit isn't. | `MainWindow.xaml.cs:1620–1627`, `Relay.Core/TunnelStats.cs:147,158` | major (i18n) | S | Android already moved its units into resources (`traffic_kb` etc.); Windows didn't |
| P-19 | **Rates and durations aren't set in tabular figures.** Windows `MonoText` is monospaced (fine). Android's traffic line and Windows' caption-style totals use proportional figures, so values shift width every second. | `HomeScreen.kt:476`, `MainWindow.xaml` `StatDownTotal`… | minor | S | No `fontFeatureSettings = "tnum"` anywhere in Android |
| P-20 | **Two different vocabularies for one state.** Advertising is "Waiting for your PC…" on the phone and "Connecting…" on the PC chip. Connected is "1 device connected" on the phone and "Connected via SM-A307FN" on the PC. The phone never names the PC. "Enter the 2-digit code" (PC) vs "Type this on your PC" (phone) is fine, but nothing is shared. | `strings.xml`, `Strings.cs` | minor | M (needs a shared copy table) | — |
| P-21 | **Plurals for a count that can only be 1.** `status_connected`, `notification_connected` and `tile_connected` carry "%d devices", but ADR-0009 allows one peer. | `strings.xml` plurals | minor | S | — |

### Accessibility

| ID | Problem | Where | Sev | Effort | Evidence |
|---|---|---|---|---|---|
| P-22 | **Windows caption text fails AA.** `CaptionText` defaults to `LabelQuaternary` (36% white). Composited on the status chip over the solid fallback, that's **3.3:1** at 11.5 px (computed from the tokens, method in the appendix). It's used for the status chip word and every Advanced label (Version, Hotspot address, Block traffic…, Kill switch, Start with Windows, Activity log). | `Styles/Tokens.xaml:36,276–280` | **major** | S | `<Setter Property="Foreground" Value="{StaticResource LabelQuaternary}" />` |
| P-23 | **Android light-theme warning text fails AA.** `#B37417` on the light glass panel is **3.67:1**. It's used as text for "Reconnecting…" in `PairingPanel` and for WARN log lines. | `Theme.kt` `LightGlass.warning`, `shared/design-tokens.json` `color.light.warning`, `HomeScreen.kt` subtitle colour | major | S (change `/shared` first) | — |
| P-24 | **No state change is announced to a screen reader on either platform.** Android sets no `liveRegion` anywhere. Windows sets no `AutomationProperties.LiveSetting` and raises no automation notifications. "Connected", "Reconnecting" and errors arrive silently. | `grep` finds no `liveRegion` / `LiveSetting` in either app | **major** | S–M | — |
| P-25 | **Android's header status is colour only.** A 10 dp dot with no semantics and no word. Windows fixed this with the chip, and DESIGN.md calls that doctrine. | `HomeScreen.kt:246` (`StatusDot`) | major | S | `Box(Modifier.size(10.dp).alpha(alpha).background(color, CircleShape))` |
| P-26 | **Enter on the Windows error panel always runs the primary action, wherever focus is.** The root `KeyDown` handler (with `handledEventsToo`) calls `OnErrorPrimaryClick` whenever the error panel is visible, so a keyboard user who tabs to Dismiss and presses Enter gets the primary action. On Idle, Enter starts the camera even when a phone row has focus. | `MainWindow.xaml.cs:451–476` | **major** | S | `if (ErrorPanel.Visibility == Visible && ErrorPrimaryButton.Visibility == Visible) OnErrorPrimaryClick(…)`. **Verify on hardware**: whether the focused button also fires its own Click first. |
| P-27 | **Windows has no high-contrast path.** `Tokens.xaml` defines one static dark palette with no `ThemeDictionaries`/`HighContrast` entries. In high-contrast mode the fixed alphas and acrylic stay, and system colours are ignored. | `Styles/Tokens.xaml` | major | M | No `HighContrast` key anywhere in `windows/` |
| P-28 | **Phone rows on Windows are one concatenated string.** `$"{device.Code}   {who}   ·   {link}"`. Narrator reads the spacing as-is, and the layout depends on literal spaces (see also P-31). | `MainWindow.xaml.cs:1335` | minor | S | — |
| P-29 | **Most explanatory copy on Android is 12 sp.** `labelSmall` (12/16) is used for warning bodies, onboarding bodies, hints, and the route explanation. That's legible at 1.0× but small for paragraphs, and 16 sp line height is tight for Persian. At 200% font scale, a 12 sp paragraph becomes long, narrow columns in banners whose actions sit beside the text (`WarningBanners` Row with button + Dismiss). | `Theme.kt` `labelSmall`; `HomeScreen.kt` `WarningBanners` | major | M | `UsbOffer` already moved its actions under the text for exactly this reason; the other banners didn't. **Verify on hardware** at 200%. |
| P-30 | **Fixed heights on Windows don't follow text scaling.** `CodeBox Height="64"` with 25 px text, `ButtonHeight` 46 (fixed, not min), the scan card 252, `StateHost MinHeight="212"`. WinUI scales text with the Windows "Text size" setting by default, so at large sizes button labels and the code box will crowd or clip. | `MainWindow.xaml:105,138,185`; `Tokens.xaml:34,43` | major | M | **Verify on hardware** at 200% text size |

### Persian / RTL

| ID | Problem | Where | Sev | Effort | Evidence |
|---|---|---|---|---|---|
| P-31 | **Mixed-direction strings are assembled by concatenation.** On Windows, phone rows mix a Latin code, a Latin model name, Persian "وای‌فای" and "پاسخ نمی‌دهد" with plain spaces and `·`, and the stats lines mix a Persian label with a Latin number and English unit. On Android, `traffic_format` mixes `↑ 1.5 مگابایت`. The comment in `formatBytes` says the line "is already pinned LTR", but `PairingPanel`'s traffic `Text` sets no text direction. | `MainWindow.xaml.cs:1335,1620`; `HomeScreen.kt:476,1120` | major | S–M | **Verify on hardware**: bidi run order in fa-IR |
| P-32 | **The Advanced disclosure arrow doesn't mirror.** `"▾  "` / `"▸  "` is a literal prefix, so in RTL the collapsed arrow still points right. | `HomeScreen.kt:762` | minor | S | — |
| P-33 | **Digits have no rule.** Onboarding step numbers are `number.toString()` (Latin). Android plurals format `%d` with the device locale (likely Persian digits — **verify**). Traffic uses `Locale.US` (Latin). The Windows string `"واردکردن کد ۲ رقمی"` uses a Persian digit, while the code the user types is Latin. One screen can show both systems. | `OnboardingScreen.kt` `StepRow`; `strings.xml` (fa) plurals; `Strings.cs` `EnterCode` (fa) | minor | S (decide) | See the conflict note under C |
| P-34 | **Persian renders in the system face.** Estedad is the decided target, but nothing is bundled (DESIGN.md says so plainly). Line heights in `/shared` were set for Latin (caption 12/16). | `shared/design-tokens.json` `typography` | minor | M | — |
| P-35 | **Persian labels truncate on Windows.** Advanced labels are `NoWrap` + `CharacterEllipsis` in a 380 dip popover beside a toggle. "کلید قطع اضطراری (Kill switch)" and "بستن مسیرهای دور تونل" are the longest. The full meaning is only in a tooltip, which keyboard and touch users may never see. | `MainWindow.xaml` Advanced grids | minor | S | **Verify on hardware** |

### Onboarding, permissions, settings

| ID | Problem | Where | Sev | Effort | Evidence |
|---|---|---|---|---|---|
| P-36 | **Onboarding comes before the first share, and treats essentials and extras alike.** Notifications and battery affect whether sharing *works*; tile, widget and shortcut affect convenience. All five look the same, and there's no Skip (the string exists and is unused). | `OnboardingScreen.kt`, `MainActivity.kt` `onboardingSteps` | **major** | M | `onboarding_skip` is referenced nowhere in Kotlin |
| P-37 | **The notification step probably doesn't tick after it's granted.** `notifGranted` is read during composition, and the result of `requestPermissions(…, 1)` triggers no recomposition (battery is refreshed on resume and does update). | `MainActivity.kt:248` | minor | S | **Verify on hardware** |
| P-38 | **The battery prompt can appear twice at once.** `BatteryBanner` shows whenever not exempt, and `WarningCode.BATTERY_UNRESTRICTED_DENIED` maps to the same title and body in `WarningBanners`. If both are active, the person sees the same request twice. | `HomeScreen.kt:210`, `:296` region | minor | S | — |
| P-39 | **The approval dialog is the only off-system surface.** It uses Material's default `AlertDialog` rather than the glass components, and it's the one screen that carries a security decision. | `HomeScreen.kt:416` | minor | S | — |
| P-40 | **Advanced on Android mixes five unrelated things.** Version, address, theme, routing (with a text field), and the log share one panel. The routing section alone has a title, a 40-word hint, chips, a status line, a field, validation and scan results. | `HomeScreen.kt` `AdvancedSection` | minor | M | — |
| P-41 | **One-accent rule broken in several places.** Android: connected subtitle + "Over USB" both accent; warning **Dismiss**, **Share report**, **Clear** and the found-proxy link are all accent text on one screen. Windows: one found phone is a `PrimaryButton` while `CodeHintText` turns accent on match. | `HomeScreen.kt:332,473,849`; `MainWindow.xaml.cs` `Fill`, `OnShortCodeTyped` | minor | S | DESIGN.md → "The One Accent Rule" |

### Motion and loading

| ID | Problem | Where | Sev | Effort | Evidence |
|---|---|---|---|---|---|
| P-42 | **Android has no explicit reduced-motion branch.** The infinite pulses on the status dot and in `PreparingPanel`, and the crossfade, rely on Compose following the system animator scale. Windows has a real code path (`Motion.Enabled`). | `HomeScreen.kt:246,391` | minor | S | **Verify on hardware** with "Remove animations" on |
| P-43 | **Loading states without a reason.** Android "Starting…" can last ~8 s while `LinkWait` waits for an address, and nothing says so. Windows Busy is better (it names the elevation prompt). | `HomeScreen.kt:391` | minor | S | — |
| P-44 | **Spacing is ad hoc on Android.** Literal 2, 3, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32 dp. Six of those (2, 3, 6, 10, 14, 20, 28) aren't in the shared scale (4/8/12/16/24/32/48), and the onboarding radius 20 dp isn't in the radius scale. | `HomeScreen.kt`, `OnboardingScreen.kt` | minor | M | Nothing test-enforces tokens (DESIGN.md says so) |

---

## C. Quick wins vs. structural changes

### Quick wins (high impact, S effort)

Each of these is self-contained and fits a small PR. Strings change in both
languages in the same PR.

1. **P-01** Make Windows "Try Again" actually retry: re-dial the last device or payload, or rename it and keep one button. *(blocker)*
2. **P-22** Raise Windows `CaptionText` to `LabelTertiary` (54%, 5.96:1). Change the `win-` value documented in DESIGN.md.
3. **P-23** Darken light `warning` in `/shared` first, then in `Theme.kt`, until it clears 4.5:1 on the light panel.
4. **P-24 + P-25** Announce state changes: Compose `liveRegion = Polite` on the status line plus a word beside the Android dot; WinUI `LiveSetting=Polite` on the status chip and headline.
5. **P-26** Scope Enter to the focused element.
6. **P-04 + P-15** Carry the PC's `name` through `ClientGate.Pending` into the dialog and notification; show "This PC: <name>" on the Windows busy panel. The contract already requires it, so `/shared` doesn't move.
7. **P-06, P-10, P-11, P-14** Copy corrections in both languages, plus `docs/errors.md`.
8. **P-18** Move Windows units into `Strings.cs` (as Android did).
9. **P-02 + P-05** A neutral fallback error, and a chip word for Error.
10. **P-32, P-38, P-41, P-21** RTL arrow, duplicate battery banner, accent clean-up, singular strings.

### Structural changes (M–L)

- **S-1 Connection hero (P-03, P-17, P-20, P-25).** One status area per platform, built from `shared/connection-states.json`: colour, icon, a one-line label and the single next action. On the phone's Connected state the code and QR collapse behind "Pair again". This needs a **shared copy table** (state → label key) in `/shared` so the two apps say the same thing.
- **S-2 Error model (P-08, P-09, P-12).** Extend `docs/errors.md` into a contract: per code, a cause, what Relay already tried, exactly one primary action from a closed set (`openWifiSettings`, `openTethering`, `retry`, `scanQr`, `enterCode`, `update`, `none`), and details. Both platforms render from it, and test vectors assert that every code has exactly one primary action.
- **S-3 Onboarding after success (P-36, P-37).** Ask for only what sharing needs, right before it's needed: notifications on first Start, battery after the first successful connection, explained in one sentence each, with a visible way back. Tile and widget become a dismissible card after the first connection.
- **S-4 Accessibility foundations (P-27, P-29, P-30).** High-contrast resources for WinUI, min-height instead of fixed height, and a type scale where body copy isn't 12 sp. Done through `/shared` tokens.
- **S-5 Token enforcement (P-44).** A test that reads `shared/design-tokens.json` and asserts the Compose theme and `Tokens.xaml` values. DESIGN.md already names this as the missing guard.
- **S-6 Remove Fast Mode remnants from the Windows client (P-07).** Less surface, fewer wrong strings.
- **S-7 Remembered PC (Job 2, steps 4–5) — needs an ADR, flagged rather than proposed.** Skipping the daily approval means the phone recognising a laptop across sessions. That changes the security model ADR-0009 settled (fresh keys per session, the human as the gate) and the pairing contract. It's the largest single cut to daily friction, and it isn't something this audit can recommend without a written argument. UAC on every connect is in the same category: removing it means a persistent privileged component, which is also an ADR.

### Conflicts with the brief, flagged instead of worked around

- **"Respects Windows accent."** DESIGN.md's One Accent Rule fixes the accent to Relay Teal, and the popover is dark-only by design. Following the system accent would replace the identity that rule 6 of the brief says to keep. **Proposed:** respect high contrast fully (P-27) and keep the teal accent. Needs a decision.
- **"Persian digits where appropriate."** The pairing code, IP addresses and ports must match what the other device shows and what a Latin keyboard types. Converting them would make the two screens disagree. **Proposed rule:** Persian digits for counts and durations in Persian prose; Latin digits for anything a person compares or types (codes, addresses, ports, versions). Needs a decision, then a line in `/shared`.
- **"44px minimum targets."** The Windows caption buttons (minimise, close) are 32×28 on purpose. DESIGN.md calls them a deliberate exception: pointer-only affordances that would make the header shout at full size. The brief has no such exception. **Proposed:** keep 32×28 visually and pad the hit area to 44×44 without moving the glyphs. Needs a decision, because it changes a documented rule.
- **"Screenshot tests in both languages and both themes."** The Android device lab already captures screenshots (`DeviceEvidence.kt`). The Windows popover has no UI automation on any runner (`docs/testing.md`, Rung 4). Windows visual checks would be a new capability, so they need care under ADR-0004 (no new local tooling).

---

## D. Five measurable UX goals

Each has a baseline counted from today's code and a way CI can check it.

| # | Goal | Baseline (counted) | How CI proves it |
|---|---|---|---|
| G-1 | **Every error surface has exactly one primary recovery action, and the action does what its label says.** | Windows: 2 codes with no action, and every `TryAgain` (10 codes) actually dismisses. Android: 8 codes, all "Try Again", 3 of which can't succeed without a settings change. | A shared error table (S-2). Unit tests on both platforms assert every code maps to exactly one action from the closed set. |
| G-2 | **All text meets 4.5:1 (3:1 for ≥ 18.66 px bold / 24 px) in every theme the platform ships.** | 2 failing pairs found: Windows caption 3.3:1, Android light warning 3.67:1. | A contrast test over `shared/design-tokens.json` and the `win-` tokens, using the method in the appendix. |
| G-3 | **Zero user-facing text outside the string stores, units included.** | Windows units in `TunnelStats.cs` (`KB/MB/GB/TB`, `/s`) and `"ms"` in `MainWindow.xaml.cs`. | Extend `StringsCoverageTests` to fail on unit literals in the formatting paths. |
| G-4 | **Every state change is announced to TalkBack and Narrator.** | 0 live regions on either platform. | An Android instrumented test asserts the live-region semantics on the status node. A Windows unit test asserts `LiveSetting` on the chip and headline. |
| G-5 | **Phone-side actions on the first-ever happy path: ≤ 2 before the PC clicks (Start, Allow), excluding OS prompts.** | 3 (Done, Start, Allow), plus a five-item checklist screen first. | `GoldenJourneyTest` already drives this path; count its interactions and assert the bound. |

---

## Appendix — how contrast was computed

WCAG 2.1 relative luminance. Alpha foregrounds were composited over the
**worst realistic ground** for each surface:

- **Windows:** the solid fallback `#12161D` (DESIGN.md: the no-transparency case), plus the chip's 5% fill.
- **Android dark:** `#0A0C10` plus 7.5% white glass.
- **Android light:** `#EEF1F5` plus 60% white glass.

| Pair | Ratio | Passes 4.5:1 |
|---|---|---|
| Windows `LabelQuaternary` 36% on status chip | 3.30 | ✗ |
| Windows `LabelTertiary` 54% on solid | 5.96 | ✓ |
| Windows `LabelTertiary` 54% on sunken fill | 5.99 | ✓ |
| Android dark `textTertiary` 60% on panel | 6.77 | ✓ |
| Android dark `error` on panel | 5.02 | ✓ |
| Android dark `warning` on panel | 7.65 | ✓ |
| Android light `textSecondary` 58% on panel | 4.65 | ✓ (narrowly) |
| Android light `accent` on panel | 5.00 | ✓ |
| Android light `error` on panel | 4.64 | ✓ (narrowly) |
| Android light `warning` on panel | 3.67 | ✗ |

Disabled controls are exempt under WCAG 1.4.3 and are not counted. The
acrylic case on Windows depends on the wallpaper behind it, which is why the
solid fallback is used as the bound.
