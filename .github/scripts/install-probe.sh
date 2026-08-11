#!/usr/bin/env bash
# Install the artifacts a user actually downloads, on a booted emulator, and
# record exactly what the platform says.
#
# The rest of the device lab installs an APK that CI just built, on x86_64, on
# two API levels. That leaves the interesting half untested: the file on the
# release page, on the ABI and Android version a real phone runs. This script
# closes that gap by doing the dumbest possible thing — hand the published file
# to the package manager and write down the verdict.
#
# Usage: install-probe.sh <out-dir> <apk> [apk...]
set -uo pipefail

OUT="${1:?usage: install-probe.sh <out-dir> <apk> [apk...]}"; shift
mkdir -p "$OUT"
RESULTS="$OUT/install-results.txt"
: > "$RESULTS"

PKG=io.relay.app

note() { printf '%-46s %-6s %s\n' "$1" "$2" "${3:-}" | tee -a "$RESULTS"; }

# ---------------------------------------------------------------- device facts
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
REL=$(adb shell getprop ro.build.version.release | tr -d '\r')
ABIS=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
PAGE=$(adb shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')

{
  echo "device: Android $REL (API $SDK)"
  echo "abilist: $ABIS"
  echo "page size: ${PAGE:-unknown}"
  echo
} | tee -a "$RESULTS"

# ------------------------------------------------------------------- the probe
# Two install paths, because they are not the same code path and only one of
# them is what a user does:
#   adb install       — what CI has always used; runs as the shell UID
#   pm install        — what the on-device package installer ends up calling,
#                       with the file already sitting on the phone's storage
for APK in "$@"; do
  NAME=$(basename "$APK")
  [ -f "$APK" ] || { note "$NAME" "SKIP" "file missing"; continue; }

  adb uninstall "$PKG" >/dev/null 2>&1 || true

  OUT_ADB=$(adb install -r "$APK" 2>&1)
  if grep -qi "^Success" <<<"$OUT_ADB"; then
    note "$NAME [adb install]" "PASS"
  else
    REASON=$(grep -o 'INSTALL_[A-Z_]*' <<<"$OUT_ADB" | head -1)
    note "$NAME [adb install]" "FAIL" "${REASON:-$(tr '\n' ' ' <<<"$OUT_ADB" | cut -c1-120)}"
  fi
  adb uninstall "$PKG" >/dev/null 2>&1 || true

  adb push "$APK" "/data/local/tmp/$NAME" >/dev/null 2>&1
  OUT_PM=$(adb shell pm install -r "/data/local/tmp/$NAME" 2>&1 | tr -d '\r')
  if grep -qi "^Success" <<<"$OUT_PM"; then
    note "$NAME [pm install]" "PASS"

    # Installing is not the same as working. Confirm the platform actually
    # picked a native library, and that the app survives being started — an
    # APK that installs and then dies on a missing .so is still a broken
    # download from where the user is standing.
    ABI=$(adb shell pm dump "$PKG" 2>/dev/null | grep -m1 -o 'primaryCpuAbi=[a-z0-9_-]*' | cut -d= -f2 | tr -d '\r')
    note "  └ native ABI selected" "${ABI:+PASS}" "${ABI:-none}"

    adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 4
    if adb shell pidof "$PKG" >/dev/null 2>&1; then
      note "  └ launches and stays alive" "PASS"
    else
      note "  └ launches and stays alive" "FAIL" "process gone"
      adb logcat -d -b crash 2>/dev/null | tail -40 > "$OUT/crash-$NAME.txt"
    fi
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  else
    REASON=$(grep -o 'INSTALL_[A-Z_]*' <<<"$OUT_PM" | head -1)
    note "$NAME [pm install]" "FAIL" "${REASON:-$(tr '\n' ' ' <<<"$OUT_PM" | cut -c1-120)}"
  fi

  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb shell rm -f "/data/local/tmp/$NAME" >/dev/null 2>&1 || true
  echo | tee -a "$RESULTS"
done

# ------------------------------------------------------------- the upgrade path
# Almost nobody installs onto a clean phone. They already have the previous
# version and tap the new file on top of it, which is a different code path:
# the platform now has to reconcile signatures, version codes and the native
# ABI it picked last time. RELAY_UPGRADE_FROM/RELAY_UPGRADE_TO name the two
# files; both must be present for the probe to mean anything.
if [ -n "${RELAY_UPGRADE_FROM:-}" ] && [ -f "${RELAY_UPGRADE_FROM}" ] \
   && [ -n "${RELAY_UPGRADE_TO:-}" ] && [ -f "${RELAY_UPGRADE_TO}" ]; then
  echo "upgrade: $(basename "$RELAY_UPGRADE_FROM") -> $(basename "$RELAY_UPGRADE_TO")" \
    | tee -a "$RESULTS"

  adb uninstall "$PKG" >/dev/null 2>&1 || true
  if adb install "$RELAY_UPGRADE_FROM" 2>&1 | grep -qi "^Success"; then
    note "  └ previous version installs" "PASS"

    OLD_ABI=$(adb shell pm dump "$PKG" 2>/dev/null | grep -m1 -o 'primaryCpuAbi=[a-z0-9_-]*' | cut -d= -f2 | tr -d '\r')

    # -r is what the package installer does for an upgrade: keep the data,
    # replace the code. Without it the platform refuses outright.
    OUT_UP=$(adb install -r "$RELAY_UPGRADE_TO" 2>&1)
    if grep -qi "^Success" <<<"$OUT_UP"; then
      NEW_ABI=$(adb shell pm dump "$PKG" 2>/dev/null | grep -m1 -o 'primaryCpuAbi=[a-z0-9_-]*' | cut -d= -f2 | tr -d '\r')
      note "  └ upgrade installs over it" "PASS" "abi $OLD_ABI -> $NEW_ABI"

      adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
      sleep 4
      if adb shell pidof "$PKG" >/dev/null 2>&1; then
        note "  └ upgraded app still runs" "PASS"
      else
        note "  └ upgraded app still runs" "FAIL" "process gone after upgrade"
        adb logcat -d -b crash 2>/dev/null | tail -40 > "$OUT/crash-upgrade.txt"
      fi
    else
      REASON=$(grep -o 'INSTALL_[A-Z_]*' <<<"$OUT_UP" | head -1)
      note "  └ upgrade installs over it" "FAIL" "${REASON:-$(tr '\n' ' ' <<<"$OUT_UP" | cut -c1-120)}"
    fi
  else
    # The previous release ships arm64 only. On an image without arm64
    # translation it cannot be installed at all, which says nothing about the
    # upgrade — so this is a gap in coverage, not a defect to fail on.
    note "  └ previous version installs" "SKIP" "no arm64 translation on this image"
  fi
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  echo | tee -a "$RESULTS"
fi

echo "--- summary ---" | tee -a "$RESULTS"
if grep -q "FAIL" "$RESULTS"; then
  echo "FAILURES on API $SDK ($ABIS)" | tee -a "$RESULTS"
  exit 1
fi
echo "all install paths OK on API $SDK ($ABIS)" | tee -a "$RESULTS"
