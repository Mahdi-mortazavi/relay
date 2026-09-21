#!/usr/bin/env bash
# Proves the APK on the phone is the APK you think it is — before any
# observation of the app's behaviour is allowed to count.
#
#   scripts/device-verify.sh                       # check what is installed
#   scripts/device-verify.sh path/to/Relay.apk     # and prove it is THAT file
#
# This exists because of a specific, expensive failure recorded in
# docs/testing.md: a stale APK invalidated every observation made before anyone
# noticed — including the conclusions drawn from them. An afternoon of careful
# reasoning about a build that was not running.
#
# The usual suggestion for this is to inject a BUILD_ID constant and read it
# back from logcat. That needs a build change, and it only proves the app
# *says* it is that build. Comparing the SHA-256 of the installed APK against
# the file on disk needs no code change at all and proves the bytes, which is
# the actual question. Android keeps the installed APK world-readable, so
# `adb shell sha256sum $(pm path)` answers it directly.
#
# Exit codes: 0 verified · 1 could not check · 2 MISMATCH (stop and reinstall).
set -euo pipefail

PACKAGE="io.relay.app"
expected_apk="${1:-}"

say()  { printf '%s\n' "$*"; }
fail() { printf '\n  ✗ %s\n' "$*" >&2; exit "${2:-1}"; }

adb_bin="${ADB:-adb}"
command -v "$adb_bin" >/dev/null 2>&1 || {
  candidate="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
  [ -x "$candidate" ] && adb_bin="$candidate" || fail "adb not on PATH. Set ADB=/path/to/adb."
}

# ── one device, and only one ────────────────────────────────────────────────
# Two attached devices is the other way to draw a conclusion about the wrong
# phone, and adb picks one silently.
devices="$("$adb_bin" devices | awk 'NR>1 && $2=="device" {print $1}')"
count="$(printf '%s\n' "$devices" | grep -c . || true)"
[ "$count" -eq 1 ] || fail "need exactly one device in 'device' state, found $count:
$(printf '%s\n' "$devices" | sed 's/^/      /')"
say "device            $devices"

# ── is it even installed ───────────────────────────────────────────────────
paths="$("$adb_bin" shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r' | sed 's/^package://')"
[ -n "$paths" ] || fail "$PACKAGE is not installed on that device."
apk_count="$(printf '%s\n' "$paths" | grep -c . || true)"
[ "$apk_count" -eq 1 ] || say "note              $apk_count APKs installed (split); hashing the first"
device_apk="$(printf '%s\n' "$paths" | head -1)"

# ── what the device says it is running ─────────────────────────────────────
dump="$("$adb_bin" shell dumpsys package "$PACKAGE" 2>/dev/null | tr -d '\r')"
version_name="$(printf '%s' "$dump" | grep -oE 'versionName=[^ ]+' | head -1 | cut -d= -f2)"
version_code="$(printf '%s' "$dump" | grep -oE 'versionCode=[0-9]+' | head -1 | cut -d= -f2)"
app_uid="$(printf '%s' "$dump" | grep -oE 'userId=[0-9]+' | head -1 | cut -d= -f2)"
installer="$(printf '%s' "$dump" | grep -oE 'installerPackageName=[^ ]+' | head -1 | cut -d= -f2)"
say "installed         $version_name  (versionCode $version_code)"
say "uid               ${app_uid:-unknown}"
say "installed by      ${installer:-none — a sideload or adb install}"

# ── the bytes on the phone ─────────────────────────────────────────────────
device_hash="$("$adb_bin" shell "sha256sum '$device_apk' 2>/dev/null || toybox sha256sum '$device_apk'" \
  2>/dev/null | tr -d '\r' | awk '{print $1}' | head -1)"
case "$device_hash" in
  [0-9a-f]*) : ;;
  *) fail "could not hash the installed APK ($device_apk). Without that this
      script cannot tell you anything, and a guess here is what it exists to
      prevent." ;;
esac
say "on-device sha256  $device_hash"

# ── where the tree stands, for the record ──────────────────────────────────
if git rev-parse --git-dir >/dev/null 2>&1; then
  head_short="$(git rev-parse --short HEAD)"
  described="$(git describe --tags 2>/dev/null || echo 'no tag')"
  dirty=""
  git diff --quiet 2>/dev/null || dirty=" (working tree dirty)"
  say "repo HEAD         $head_short · $described$dirty"
fi

# ── the comparison, if a file was named ────────────────────────────────────
if [ -z "$expected_apk" ]; then
  say ""
  say "  ⚠ No APK named, so nothing was PROVEN — only reported."
  say "    Re-run with the file you believe is installed:"
  say "      scripts/device-verify.sh <that>.apk"
  exit 0
fi

[ -f "$expected_apk" ] || fail "no such file: $expected_apk"
local_hash="$(sha256sum "$expected_apk" | awk '{print $1}')"
say "local sha256      $local_hash"
say "                  $(basename "$expected_apk")"

if [ "$device_hash" = "$local_hash" ]; then
  say ""
  say "  ✓ VERIFIED — the phone is running exactly these bytes."
  say "    Observations of this build can be trusted."
  exit 0
fi

fail "MISMATCH — the phone is NOT running that file.

      on device   $device_hash
      local file  $local_hash

      Every observation you make now is about a different build. Reinstall
      before reasoning about behaviour:

        $adb_bin install -r '$expected_apk'

      If that is refused for a signature mismatch, the installed copy was
      signed with a different key (a debug build over a release, or the
      reverse) and it has to be uninstalled first — which also clears its
      settings and its paired-PC approval." 2
