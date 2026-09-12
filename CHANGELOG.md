# Changelog

Notable changes to Relay. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
Relay is pre-1.0, so minor versions may still change behaviour.

One tag ships both platforms (`vX.Y.Z`) — see [`docs/release.md`](docs/release.md).
Artifacts for every version are on the
[Releases page](https://github.com/Mahdi-mortazavi/relay/releases).

## [Unreleased]

## [2.8.2] — 2026-09-12

Every item here came out of **one diagnostic log**, sent by a user on Telegram.
The pattern in all of them is the same: Relay had the information and did not
write it down.

### Fixed — turning on USB tethering and pressing Start no longer loses a race

Android takes a few seconds to give a freshly-enabled tethering interface an
address. Relay looked exactly once, saw nothing, and said *"No usable Wi-Fi or
hotspot interface found"* — so switching tethering on and immediately pressing
Start failed, seven times in a row in the log this came from, and then worked
without the user changing anything.

It now looks eight times over about eight seconds. The first look still costs
nothing, so a phone already on Wi-Fi starts exactly as fast as before.

### Fixed — the message now says which link is missing

That one sentence covered four different situations, and told three of those
users to do something they had already done. It is now four messages:

| What Relay found | What it says |
|---|---|
| A link up with no address yet | **"Your cable is still connecting."** Wait and try again — the only one of these that does not ask you to change anything |
| Only mobile data | Turn on Wi-Fi, the hotspot, or the cable |
| Only the phone's own VPN | The same — and it says plainly that **your VPN stays on and Relay will share it** |
| Nothing at all | The original message, unchanged |

### Fixed — a VPN warning that fired on connections that were working

*"Your VPN is blocking Relay"* was raised from a route lookup that predicts which
interface a reply *would* take. The log settled what that is worth: the check
flipped its verdict four times in one session, announced that replies were lost,
and sixty-four seconds later that phone paired a PC and carried its traffic.

A warning that fires on a working connection is the reason the next real one is
not believed. It is replaced by one built on what the phone actually observes —
a PC took the settings, and the tunnel was never handshaked in the twenty seconds
that followed. That is the exact signature of the fault and of nothing else, and
it appears at the same moment the PC gives up, which is what the PC's own message
has always promised.

### Fixed — Windows updates now actually install

Found on the maintainer's own laptop, which was running a build from 16 August
while 2.8.1 was published. A 48 MB installer sat in the temp folder, downloaded
and checksum-verified, **never run**, for three days.

Installing could only happen inside a single run of the app: it had to still be
open, to have been open a while, and to be disconnected, all at the same time.
Relay is opened in order to be *connected* to, so someone who opens it, connects,
and closes it when they are done never met that. Every launch started again from
nothing and downloaded the same fifty megabytes.

A verified download is now remembered, and goes in **when you close Relay** —
which costs you nothing — or at the next launch if the app never got a clean
exit. Its hash is checked again before it runs. The daily check no longer
re-downloads something already on disk, and the first check happens 30 seconds
after launch rather than two minutes, which is longer than many whole sessions.

Separately: an update now installs **over the copy that is running**. Windows
remembers the previous install's folder, and a silent install accepts it with no
dialog for anyone to correct — on that laptop it still pointed at a directory
deleted months earlier, so the update would have installed itself perfectly
somewhere nobody launches Relay from, and reported success.

### Changed — the diagnostic report is worth reading

Every line now carries a level (INFO / WARN / ERROR), an area, and named fields
instead of values buried in prose. A failure records **every network interface
the phone had at that moment**, and why each one was refused:

```
ERROR link  No usable Wi-Fi, hotspot or USB link  code=LINK_NEGOTIATING
            links="rndis0(no-ipv4) wlan0(192.168.1.5 wifi score-3) tun0(10.8.0.2 vpn)"
```

`rndis0(no-ipv4)` is the answer that previously took a message to the user, in
another country, and a day. The report ends with a count of its own errors and
warnings. [How to read one](docs/diagnostics.md).

Two things were wrong in it and are fixed. Its header claimed the clock started
when sharing did; it started when the app did, so a report from a phone open for
ninety minutes began at `4999.58` and read like it came from another machine. And
timestamps followed the phone's language setting — on a Persian phone every
number in a shared report came out in Persian-Indic digits. A report the
maintainer cannot read is a report that was not sent.

## [2.8.1] — 2026-09-10

### Fixed — the phone tells the laptop which way it reached it

A phone with a cable in *and* Wi-Fi on sends its beacon out of both, and the
laptop had no way to tell which copy came by which route. Each datagram now
carries the address of the interface it left by.

### Added — the reply path is recorded before a PC ever knocks

The phone writes a `Reply path checked` line into its diagnostic log when sharing
starts, so a report from a phone whose VPN swallows Relay's replies says so even
when the pairing never got far enough to produce a warning. **Log-only on
purpose** — and 2.8.2 explains why that was the right call.

### Changed

The cable offer says the cable exists where people actually look for it.

## [2.8.0] — 2026-09-07

### Added — connect over the USB cable

Plug the phone into the laptop and Relay uses the cable. No Wi-Fi in range, no
hotspot draining the battery, nothing sharing the airwaves with it.

Because USB tethering is a *system* setting that no app permission can switch
on, Relay does the two things it is allowed to do: it notices the cable and it
opens the right screen. A card appears while you are sharing — *"Cable to your
PC?"* — and its button takes you straight to Android's tethering switch. Turn it
on and the phone says **Over USB**; the PC shows the link on the row it is about
to connect to, and again on the address line once it has.

The card asks rather than announces, on purpose. Android gives no public way to
tell a computer from a wall charger on the other end of a cable, so this is a
good guess and is worded like one — and **Not now** makes it go away until you
plug in again.

What it does *not* claim is that a cable is faster. That was never measured, and
a good 5 GHz link can beat USB 2.0. What is true by construction: nothing else
shares the medium, it works with no Wi-Fi in range at all, and no battery goes
on holding an access point up.

### Fixed — a phone on two links at once

A phone with a cable in *and* Wi-Fi on has two addresses, and the beacon only
ever carried one of them. Every copy of the datagram was built from a single
chosen address and then broadcast on every interface — so the laptop on the
cable was handed the phone's *Wi-Fi* address, which it has no route to. The
failure looked like this: the phone appears in the list, showing the right code,
and connecting times out for no visible reason.

Each datagram now carries the address of the interface it leaves by, and names
what kind of link that is. The PC ranks them and takes the cable.

**Two live addresses are no longer read as a phone that moved.** The old rule —
"a different address for the same code means the phone moved" — turns a phone on
two links into a phone changing house once a second, with the PC re-pointing its
tunnel back and forth for as long as both are up. A different address is a *new*
address only once the previous one has gone stale; while both are fresh they are
two paths to one phone.

**And one phone on two links was being listed as two phones.** The count that
decides "which phone has this code?" was counting *paths*, so a single phone
reported `ERR_CODE_AMBIGUOUS` and appeared twice on the pairing screen, asking
you to choose between two rows that were the same device.

A phone with the hotspot up and a cable in also put its hotspot address in the
QR about half the time — the two scored equally, and the winner was decided by
whichever network interface the system happened to list first.

### Fixed — English text on the Persian UI

The link labels ("USB", "Wi-Fi", "hotspot") were about to ship as English words
baked into shared code, where no translation could reach them. Every user-facing
string in this app exists in both languages; Windows has enforced that since six
keys once shipped missing, and **Android now enforces it too** — the same names,
the same plurals, and the same format arguments in both. A translation that
drops a `%d` throws when it is shown, and only for the people reading Persian.

### Verified on hardware

Not only in CI, which has no cable to test with: a phone and a laptop, both
links live, released 2.7.1 as the PC client. It listed one phone, chose the
cable address, and carried 35.5 MB three times — with the tunnel's packets on
the cable and Wi-Fi idle.

## [2.7.1] — 2026-08-27

### Fixed — the approval prompt could not be answered, and then stopped appearing

Reported as a VPN bug: with a VPN running on the phone, pressing Connect on the
PC never brought up the "allow this computer?" prompt, and turning the VPN off
made it work again. Reproduced on a phone, where the VPN turned out to be a red
herring and two separate faults were hiding behind it.

**The prompt only existed inside the app's own screen.** It reached someone
already watching their phone and nobody else — which is the opposite of the
normal case, because you press Connect on the laptop, where you are looking,
while the phone lies face down on the desk. Measured with Relay in the
background: the request timed out after twenty seconds having shown nothing at
all — no dialog, no notification, no sound.

The question is now asked in the notification shade too, with **Allow** and
**Deny** on it, so it can be answered without opening the app.

**And a missed prompt was remembered as a refusal.** Refusing when nobody
answers is deliberate and has not changed — a phone in a pocket must fail
closed. But recording it was wrong: nobody had decided anything, and yet every
later attempt from that computer was turned away in milliseconds without anyone
being asked. The one prompt that was missed was the only prompt there would ever
be. The only way out was restarting sharing — and toggling a VPN happens to
restart sharing, which is the whole of why this looked like a VPN problem.

A person's answer is still remembered. Silence no longer is.


## [2.7.0] — 2026-08-25

Four defects on the path every byte takes, found by reading it end to end and
then measuring it. Numbers below are from benchmarks that now run on every pull
request, so the next regression here is visible rather than silent.

### Fixed — every connection died after five minutes

The forwarder set a deadline once, five minutes out, and called it an idle
timeout. It was an absolute one: a connection carrying traffic the whole time
was still torn down five minutes after it opened. Large downloads, video calls,
SSH sessions and websockets all failed at the same mark — which from the outside
is indistinguishable from a flaky network, and was reported as instability.

The deadline now moves with the traffic, and it belongs to the pair rather than
to each direction: a download is silent upstream from the first byte to the
last, so a per-direction timeout would reap exactly the transfers it exists to
protect.

### Fixed — leak protection broke `localhost`

The IPv6 rule was written as "all of `ALE_AUTH_CONNECT_V6`", and that is
literally all of it — Windows classifies loopback at that layer too, so it took
`::1` with it. Windows resolves `localhost` to `::1` before `127.0.0.1`, so
while Relay was connected every localhost connection on the machine failed or
stalled: development servers, database clients, apps talking to their own
helpers. Unrelated software breaking, and the tunnel getting the blame.

Loopback is now permitted above the blocks. This cannot leak — loopback never
reaches a network interface, so there is no adapter for it to escape by.

### Fixed — the phone leaked a pooled buffer for every packet

The read path took a buffer from the network stack's own pool for each packet
and never gave it back, so the pool never recycled and the garbage collector
chased the difference — on the phone, on the one processor that measurement
keeps finding to be the limit. Per packet, in both directions, for the life of
every session.

That path is now **3.3× faster and allocates 57% less**. A second queue and a
thread hand-off per packet went with it, which also means the buffering that was
deliberately cut to hold latency down is now the size it was meant to be
(it had been quietly double).

Honest note: this does **not** make downloads faster. End-to-end throughput is
governed by encryption, and measured the same before and after. What it buys is
lower latency under load, and less work and memory on the phone.

### Fixed — UDP held its resources five times too long

Every DNS lookup opens a flow, and each one held two threads, a socket and a
64 KB buffer until it was reaped. UDP never says when it is finished, so that
was five minutes per lookup; a browsing session accumulated hundreds. Now one
minute, which is still generous for the flows that legitimately persist.

### Changed — you are told when the tunnel comes up unprotected

If the filters cannot be installed the tunnel still comes up, deliberately: a
leak is bad and a Relay that refuses to connect is worse. But the only place
that was said was a log nobody reads, while the switch still read "on" — so the
person had been told they were protected and would act on it. It now says so on
screen.


## [2.6.1] — 2026-08-25

### Fixed — the Windows updater could not work on the connections Relay is for

2.6.0 connected the updater. Testing it on a real machine showed it still could
not finish, for two reasons that only appear outside a CI runner.

**It waited to be disconnected before downloading.** On the connection this was
tested from, `api.github.com` answers in under a second and GitHub's release
CDN returns zero bytes in five minutes. That is not an unlucky network — it is
the network Relay exists for. Waiting for the tunnel to be down before
downloading meant waiting for the one state in which the file cannot be
reached, so the check found the new version every day and never got a byte of
it. Relay now downloads whenever it can and waits only to *install*, which is
the part that would cost you your connection. The download can use your phone's
data; once per release, that is the better of the two mistakes.

**It held the whole installer in memory, on one deadline.** Fifty megabytes
buffered in RAM, and a single ten-minute timeout covering the entire transfer —
so a slow link simply ran out of time and, because this path is deliberately
silent, gave up until the next day. It streams to disk now, hashing as it goes,
under a name nothing will run until the hash matches.

## [2.6.0] — 2026-08-25

### Fixed — Relay could not update itself, on either platform

The check, the version comparison, the download, the checksum verification and
the installer launch were all written, all tested, and called by nothing. On
Windows, `UpdateCheck` and `UpdateInstaller` had sat unused for three releases.
On Android, `checkForUpdate` had no callers at all, so the update banner in the
app has never appeared for anybody. A Windows user stayed on whatever version
they first installed, while the README said updates were offered.

They are connected now:

- **Windows** checks a couple of minutes after launch and then daily, tells you
  what it found, and installs it at the next moment the tunnel is down. It never
  interrupts a connection — the installer stops Relay to replace it, and doing
  that mid-call would drop the call.
- **Android** checks when you open the app *and* when sharing starts, so someone
  who only ever uses the Quick Settings tile or the widget finds out too. Android
  does not let a sideloaded app install silently, so it offers; the last tap is
  always yours.

Nothing is installed that was not verified against the checksums the release
published.

Windows also comes back after updating itself. It now closes before running the
installer — otherwise Setup stops on a "please close Relay" dialog that a silent
install does not suppress and nobody is there to answer — and Setup starts it
again afterwards.


## [2.5.0] — 2026-08-25

Two things a user found, both fixed and both checked on the machines they were
reported from.

### Fixed — traffic was leaving around the tunnel

Reported with the detail that made it findable: it happened on a Wi-Fi the
laptop shared with the phone, and never on the hotspot the reporter normally
uses. That asymmetry names the cause.

**DNS.** The tunnel sets a resolver on its own adapter, but Windows resolves
names on *every* interface at once. On a hotspot the only other resolver is the
phone, so nothing shows. On a shared Wi-Fi the other resolver is the router —
and a leak test then lists the local ISP beside the tunnel's exit.

**IPv6.** The client configures IPv4 and nothing else. No v6 address, no v6
route, no v6 block. On any network with working IPv6, every v6 connection left
by the physical adapter carrying the real address, and the tunnel never saw it.

Both are now closed with Windows Filtering Platform rules the tunnel installs
for itself: block IPv6, block DNS, permit DNS only to the tunnel's resolver.
The filters live in a session marked dynamic, so Windows destroys every one of
them when the tunnel process ends — including when it is killed or crashes. A
dead Relay cannot leave a machine that resolves no names, which is what makes
failing closed safe rather than reckless.

Deliberately narrower than a full kill switch. Relay's own discovery keeps
working, so following a phone that changes address survives. There is a switch
in Advanced for anyone who needs the filters off, and it says what it trades.

The first attempt at this shipped and did nothing: the library Relay borrowed
needs a Windows service account, and Relay's tunnel is an elevated user process
by design. It reported success while installing no filters. That is why the
tunnel now says `LEAK-PROTECTION-FAILED` out loud when it cannot protect you.

### Fixed — 2.2 moved data more slowly than 2.0

While connected, the byte counters update once a second. Each update rebuilt the
foreground notification *and* pushed a fresh view across a process boundary into
the launcher, on the phone CPU that is already the limit. Neither surface shows
bytes — the notification counts devices, the widget shows the code — so that was
a round trip every second to redraw identical pixels. Both are now skipped
unless something visible changed.

Measured on two machines on one Wi-Fi, before and after: **5.66 → 14.89 Mbps**
down.

### Changed — the phone does less work per packet

The tunnel handed WireGuard one packet at a time where it is built to take up to
128, so every packet paid the whole per-batch cost. TCP options gVisor leaves
off are now on, including SACK, which matters because this traffic crosses Wi-Fi
twice. The packet queue was over a second deep on a slow link and is now a
quarter of that. Connection splicing reuses pooled buffers instead of allocating
64 KB per direction per connection.

Honest note: on the hotspot topology none of this is measurable, because the
tunnel is not the bottleneck there. Latency through it is 10 ms against 11 ms
for the bare hotspot.

## [2.2.0] — 2026-08-20

### Added

- **A mark of its own.** A signal arcing from one point to another, white on a
  saturated ground, drawn to survive 16 px. Android had been carrying a leftover
  play triangle and Windows whatever .NET puts on an unbranded executable.
- **Quick Settings tile**, with the pairing code in its subtitle, so the shade
  answers "what do I type on the PC" without opening anything.
- **Home screen widget** showing the code large enough to read while you are
  looking at the laptop.
- **Long-press the icon → Start Sharing**, available before the app has ever
  been opened.
- **First-run setup** that walks through notifications, battery exemption, the
  tile and the widget — and adds them for you where Android allows it.
- **Start with Windows**, using the per-user key so it needs no elevation.
  Launched that way Relay comes up in the tray rather than over your work.
- **Updates that install.** The download is checked against the SHA256SUMS the
  release publishes, and nothing that fails verification is kept or run.

### Fixed

- **The window could not be closed.** It was created without a title bar, so
  there was no close button, no minimise, nothing — and clicking away stops
  working once a connection is up. It draws its own controls now. Close puts
  Relay in the tray; Exit on the tray menu still quits.
- **Minimise minimised to the wrong place**, because the tray auto-hide read the
  deactivation as focus moving elsewhere.
- **The update banner's button did nothing.** It had never been wired up.
- **The tunnel now follows a phone that changes address** instead of dialling a
  DHCP lease that has moved.


## [2.0.0] — 2026-08-18

One transport instead of two, a two-digit code that works for it, and the
tunnel finally telling the truth about itself.

### Changed — Fast Mode is gone (ADR-0009)

Relay had two transports. Fast Mode was a **system-wide SOCKS5 proxy**, and it
is removed. Not because it was broken: it was measured working on hardware, and
`curl --socks5-hostname` returned the phone's VPN exit while the laptop's own
exit differed.

It is gone because of what it cost. A machine-wide proxy mutation, a rollback
protocol, a crash-recovery hook, and this project's most dangerous failure — a
stranded SOCKS proxy breaks every application on the machine, and most of the
safety machinery in the Windows client existed for that one risk. What it bought
was TCP-only sharing, so games, calls, installers and anything using UDP were
never actually shared. Telegram Desktop was the common report: the browser
worked, everything else did not, because only some programs honour a system
proxy.

There is now one transport, a real WireGuard tunnel, and **every** application
goes through it.

### Added — pair with the two digits, not just the QR

Full Mode's keys live only inside the QR payload, so a laptop with no camera had
no way in at all. Keys still cannot ride the beacon — it is broadcast and
unauthenticated — so the beacon carries a `pairingPort` and the configuration
comes over a short TCP exchange that is held until the person holding the phone
allows it. The phone mints keys only after Allow, sends them once, and discards
them when sharing stops.

Phones already sharing now appear on the PC's first screen, so one click is the
whole pairing when the list is not empty.

### Added — the connected screen says what is happening

A notification when the tunnel comes up, and live download and upload rates,
totals, tunnel latency and connection duration — every figure read from the
adapter Windows created, so it cannot drift from reality. Latency is measured to
the tunnel's own peer and labelled as such, rather than blaming Relay for the
round trip to a website.

### Fixed

- **"Connected" over a tunnel that had never handshaked.** The client reported
  ready as soon as the WinTun adapter existed, which is equally true of a tunnel
  whose peer is gone or whose keys have rotated. One field report showed four
  `Connected` lines against zero peers seen by the phone. It now waits for a real
  handshake, exits when the peer stops answering, and the app watches for that.
- **Full Mode never reached the Connected state.** It dispatched one state
  transition where Fast Mode dispatched two, so a live tunnel carrying traffic
  displayed "Connecting…" with a Cancel button for as long as you cared to look.
- **Connections opened before the tunnel stayed outside it.** Windows binds a TCP
  connection to a source address for life, so long-lived ones — Telegram's, in
  particular — kept leaving by the adapter they were born on. They are now closed
  when the tunnel comes up so their owners reconnect through it; loopback and the
  local network are left alone.
- **The phone now says when its own VPN is swallowing the tunnel.** Android
  routes by UID, and a full-tunnel VPN claims Relay's, so the handshake reply
  went into the VPN instead of to the laptop. It cannot be fixed from inside the
  app, so it is detected and named, with the one action that helps.
- **Windows refusing to elevate from the install folder** is reported as itself.
  Behind a junction — `%LOCALAPPDATA%\Programs` redirected to another drive —
  ShellExecute fails before any prompt appears, and Relay used to blame other
  VPNs.
- **The popover stopped discarding what you were doing.** It hid on any focus
  loss, so typed codes were lost and the window vanished mid-connect, including
  when the elevation prompt took focus.
- **Relay has a way back to itself.** It appears in Alt-Tab, stays put while
  connected, and stops floating once it is no longer transient — Windows 11 hides
  new tray icons, so the documented route back was behind a chevron.
- **Advanced is reachable at any window size.** The window is capped by its own
  maximum and again by the monitor's work area, and nothing scrolled, so
  expanding Advanced pushed content under the bottom edge of a window that cannot
  be resized.
- **Errors stopped recommending a mode that no longer exists.**

### Removed

Fast Mode, the in-repo SOCKS5 server, the system-proxy session with its
snapshot, rollback and crash-recovery hook, and the `--restore-proxy` entry
point. Nothing Relay does now outlives its process.

### Known limitations

- **Sharing a phone VPN that captures Relay's UID does not work yet.** Inside the
  VPN the tunnel's own transport is swallowed; excluded from it, the forwarded
  traffic is the phone's plain connection. Relay detects the first case and says
  so. Routing forwarded traffic through the VPN app's local proxy is the
  intended fix.
- One client per sharing session: the endpoint is configured with a single peer,
  so the QR and the code deliver the same configuration rather than two.
- No split tunnelling or per-application routing; the tunnel is all or nothing.


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
