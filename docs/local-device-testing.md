# Testing on your own phone and laptop

The device lab in CI is thorough and it has a hole it cannot close: the emulator
and the Windows runner are **two different machines in two different jobs**.
Relay's whole job is to get one machine's traffic through another over a real
Wi-Fi, and that has never once happened in CI.

A phone plugged into the laptop closes it. Not because the hardware is more
"real" — the emulator runs the same APK — but because this is the only
arrangement where the two apps have to find each other across a network with a
firewall in its default state.

## What this setup can prove that CI cannot

| | Why CI can't |
|---|---|
| The PC finds the phone through an unconfigured Windows Firewall | The runner has no phone to find, and its firewall state is not a user's |
| Pairing over real Wi-Fi (or the phone's hotspot) | The two jobs share no network |
| The system proxy actually carrying a browser's traffic | Nothing on the runner browses |
| Full Mode's UAC prompt, and what happens when you decline it | The runner has no interactive desktop |
| Battery, doze, screen-off over an hour | Emulators do not sleep like phones |
| That the phone's code and the PC's box agree, to a person holding both | No one is holding anything |

Everything else — the golden journey, Full Mode's handshake, the installer's
layout, the proxy rollback — is already covered on every pull request. **Do not
re-do that work by hand.** This setup exists for the six rows above.

## What you need on the laptop

The normal loop needs no local build (ADR-0004); these are only for driving
hardware.

| | Why | Skip it if |
|---|---|---|
| **Claude Code** | A cloud session cannot see a USB device or your desktop | — |
| **`adb`** (Android platform-tools, ~10 MB) | Everything on the phone side | never |
| **Git Bash** or WSL | `.github/scripts/*.sh` are bash | you only run the Windows half |
| **JDK 17 + Android SDK** | to run the instrumented suite against your phone | you only test the released APK by hand |
| **.NET 8 SDK** | to run `windows/Relay.App.Tests` and `Relay.E2E.Tests` locally | you let CI run them |

Phone: **Developer options → USB debugging** on, then `adb devices` must list it
as `device` (not `unauthorized` — accept the prompt on the phone).

You do **not** need Visual Studio. Building the WinUI app locally is possible but
rarely worth it: push instead, let CI build the installer, and download it.

## The two loops

### 1. The released artifacts (no build, ~2 minutes)

The honest version of what a stranger gets:

```bash
adb install -r Relay-android-universal.apk       # or the arm64 split
# install Relay-Setup-x64.exe on the laptop the normal way
```

Then pair the way the README tells a user to, and watch for the things in the
table above. This is the loop for "does the shipped thing work".

### 2. The instrumented suite against your phone (needs the Android SDK)

`device-tests.sh` is not emulator-specific — it drives whatever `adb` sees:

```bash
adb devices                                       # exactly one device attached
.github/scripts/device-tests.sh ./e2e-out
```

That installs the debug + androidTest APKs, runs the golden journey, Full Mode
and pairing discovery **on your phone**, and fails if any required class
silently skipped. Evidence lands in `./e2e-out`.

With the laptop's own Windows app instead of a runner's, the cross-platform leg
is the interesting one:

```bash
.github/scripts/cross-platform-e2e.sh ./e2e-out   # phone holds a session, host client connects
```

## What you cannot read, and what to use instead

Both apps keep their log **in memory only** — a ring buffer behind
*Advanced → Logs*, not a file. There is nothing on disk for a script to tail.
So instrument from outside:

| Want to know | Read |
|---|---|
| Is the phone announcing itself? | `adb logcat -s RelayBeacon:*` |
| Is the PC hearing it? | the phone list under the code box — it *is* the readout |
| Did the proxy get set? | `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings` → `ProxyEnable`, `ProxyServer` |
| Did the proxy get restored? | same key, compared against a snapshot taken before connecting |
| Is traffic really flowing? | `curl -x socks5h://<phone-ip>:<port> https://example.com` |
| Is the tunnel up (Full Mode)? | `Get-NetAdapter` for the WinTun adapter, `Get-NetRoute` for its routes |

The released APK is minified and not debuggable, so `adb shell run-as io.relay.app`
will fail against it. The instrumented suite installs the **debug** build, where
`run-as` works — that is why the scripts can read the phone's evidence directory.

## The firewall check — the one that matters most

Relay's Windows listener is an unelevated app and its installer is per-user, so
it cannot add a firewall rule. It therefore probes, and the phone answers unicast
(`shared/pairing-beacon.md` → The probe). Verify on a machine that has **never**
been told to allow Relay through, with the network profile set to **Public**:

1. Phone: Start Sharing. Laptop: open the code box.
2. The phone must appear in the list, with the same two digits it is showing.

If it only appears after you click **Allow** on a Windows firewall prompt, the
probe path is not working and **every fresh install will look broken**. That is a
release-blocking bug, and this is the only place it can be caught.

## Reporting what you find

A bug found here needs the same standard as one found in CI: a failing test
first, in the suite that should have caught it, then the fix. If the bug is
structurally invisible to CI — one of the six rows at the top — say so in the
commit and add the manual check to `docs/testing.md` instead of pretending a
test covers it.
