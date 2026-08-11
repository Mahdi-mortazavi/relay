# When Android says "App not installed"

Android reports almost every installation failure with the same four words and
no reason. This page maps that message onto the handful of things that actually
cause it, most likely first, with the fix for each.

Before working through it, one thing is worth knowing: every release is
installed on emulators running Android 11, 12, 14, 15 and 16 before it reaches
you — through both `adb install` and the `pm install` path the on-device
package installer uses, and including the upgrade path, where the previous
release is installed first and then replaced. That check is the
[Install matrix](../.github/workflows/install-matrix.yml) workflow, and it runs
on every published release. So if the file refuses to install on your phone
while the same file installs on those, the cause is almost certainly on the
device rather than in the download.

## Get the real error first

"App not installed" is the UI's summary. The platform's actual reason is one
line, and it names the problem exactly. With the phone connected over USB and
USB debugging on:

```
adb install Relay-android-universal.apk
```

That prints something like `INSTALL_FAILED_UPDATE_INCOMPATIBLE` or
`INSTALL_FAILED_VERIFICATION_FAILURE`. Find that string below. This path also
bypasses Play Protect, so if the install succeeds this way but fails when you
tap the file, the answer is Play Protect and nothing else.

## The causes, in the order they actually happen

### An older copy is still installed, from a different build

**Reason string:** `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, or
`INSTALL_FAILED_ALREADY_EXISTS`.

Android refuses to replace an installed app unless the new file is signed with
the same key. Every published release shares one key, so release-to-release
upgrades are fine — but a copy you built yourself, or one someone sent you, is
signed with a different key and will block every official build from
installing over it.

**Fix:** uninstall the copy you have, then install again. Uninstalling removes
its data; there is nothing in Relay worth preserving across that.

```
adb uninstall io.relay.app
```

### Play Protect blocked it

**What you see:** sometimes a distinct "Blocked by Play Protect" or "unsafe
app" dialog, but on some builds simply "App not installed".

Google Play Protect scans sideloaded apps and blocks ones it does not
recognise. Relay is signed, but with a self-managed key rather than one Google
has seen before, so a new release can trip it.

**Fix:** in the blocking dialog choose **Install anyway** / **More details →
Install anyway**. If no dialog appeared, install over `adb` as above, which
does not go through Play Protect.

This is the one failure mode on this page that the project cannot fix from its
side alone — it goes away when the app is distributed through a store, which is
tracked in [`release.md`](release.md).

### The file is for the wrong CPU

**Reason string:** `INSTALL_FAILED_NO_MATCHING_ABIS`, usually surfaced as "app
isn't compatible with your device".

`Relay-android-arm64-v8a.apk` only contains 64-bit ARM code. A 32-bit ARM
phone, an x86 Chromebook or an emulator cannot run it.

**Fix:** download `Relay-android-universal.apk` instead. It carries every CPU
type and installs everywhere, at the cost of being about three times larger.

### You downloaded the app-store bundle

**What you see:** "Can't open file", "There was a problem parsing the package",
or nothing at all.

`Relay-android.aab` is a bundle for app stores to process. It is not an app and
no phone can install it.

**Fix:** download one of the two `.apk` files.

### The download was truncated

**Reason string:** `INSTALL_PARSE_FAILED_NOT_APK`, or a parsing error.

A download interrupted by a dropped connection leaves a file that looks
complete but is not.

**Fix:** every release ships `SHA256SUMS.txt`. Compare it:

```
sha256sum Relay-android-universal.apk
```

If the hash does not match the line in that file, download it again.

### Not enough free space

**Reason string:** `INSTALL_FAILED_INSUFFICIENT_STORAGE`.

Installation needs meaningfully more room than the file itself — Android keeps
the APK, the extracted code and the optimised code at once.

**Fix:** free up a few hundred megabytes and retry.

### Installation from unknown sources is off

**What you see:** the installer refuses to start, or sends you to a settings
screen.

**Fix:** Android grants this per-app, to whichever app is opening the file.
Allow it for your browser or file manager: **Settings → Apps → [that app] →
Install unknown apps → Allow**.

## If none of these is it

Open an issue with the output of `adb install`, your Android version, and your
phone model. The reason string is the part that matters — with it the cause is
usually obvious, and without it every answer is a guess.
