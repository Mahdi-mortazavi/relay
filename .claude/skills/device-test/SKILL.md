---
name: device-test
description: Run Relay against the phone plugged into this laptop and the Windows app installed on it, find what breaks, and fix it. Use when the user asks to test on real hardware, test on their phone, check pairing on their own Wi-Fi, or verify a release on their own machine. Only meaningful in a session running locally on the laptop — a cloud session has no USB device and no desktop.
---

# Testing Relay on this laptop and the phone attached to it

Read [`docs/local-device-testing.md`](../../../docs/local-device-testing.md)
first — it lists the six things this arrangement proves that CI structurally
cannot, and the readouts to use because **neither app writes a log to disk**.

Everything else is already covered on every pull request. Do not spend a session
re-doing by hand what the device lab already does.

## Before anything

```bash
adb devices          # exactly one, listed as `device`
```

`unauthorized` means the phone has not accepted this laptop's key — the prompt is
on the phone's screen. No device at all usually means USB debugging is off, or a
charge-only cable.

Then establish which build you are testing, and say it out loud in your report:
the **released** APK/installer (what a stranger gets) or a **local debug** build
(what the instrumented suite installs). Conclusions about one do not transfer to
the other — the release APK is minified and not debuggable.

## The order to work in

1. **The firewall check first.** It is the most likely to be broken, the most
   damaging when it is, and the only place it can be caught. `docs/local-device-testing.md`
   → "The firewall check". If the phone does not appear in the PC's list while it
   is plainly showing a code, stop and diagnose that before anything else.
2. **The pairing a user actually performs** — phone shows two digits, PC lists
   the phone, one click connects. Check that the two screens agree.
3. **Traffic**, through the real system proxy: `curl -x socks5h://<host>:<port> https://example.com`,
   then a browser.
4. **Disconnect**, and confirm the system proxy came back to the snapshot you
   took *before* connecting. A dead proxy left in `HKCU` breaks every app on the
   machine and is the worst failure this product has.
5. **Full Mode**, if the phone offers it: the UAC prompt should appear once, for
   the tunnel process only. Decline it deliberately at least once and confirm the
   app says so (`ERR_WG_ELEVATION_DECLINED`) rather than reporting a generic
   failure.

## When you find a bug

Do not fix it in place and move on. In order:

1. Reproduce it deliberately — twice, so it is a bug and not a fluke.
2. Find the layer it lives in and write the failing test **there**: a shared
   vector if the two platforms disagree, a unit test if one platform is wrong, an
   instrumented test if it only shows on a device. Confirm the test fails on the
   current code.
3. Fix it. Confirm the test passes and the hardware agrees.
4. If the bug is structurally invisible to CI, say so in the commit message and
   add the manual check to `docs/testing.md`. Do not imply a test covers what no
   test can reach.

Then push and open a draft PR as usual; CI is still the gate.

## Reporting

State plainly what you ran, on which build, what passed, and what you could not
check. "Everything works" is not a report — name the checks. If you skipped one
because the hardware was not in the right state (no hotspot, no second network,
phone on a charge-only cable), say which and why rather than quietly narrowing
the scope.
