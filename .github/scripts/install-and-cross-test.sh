#!/usr/bin/env bash
# Installs the real APK on the booted emulator, then hands over to the
# cross-platform leg (host client ↔ live phone).
#
# Usage: install-and-cross-test.sh <output-directory>
set -euo pipefail

OUT="${1:?usage: install-and-cross-test.sh <output-directory>}"
PKG=io.relay.app
EVIDENCE="/sdcard/Android/data/${PKG}/files/e2e"

mkdir -p "$OUT"

collect() {
  adb pull "$EVIDENCE" "$OUT" >/dev/null 2>&1 || true
  # See device-tests.sh: adb blocks forever once the emulator is gone, so the
  # log has to be pulled while the device is still alive.
  timeout 120 adb logcat -d > "${OUT}/logcat.txt" 2>/dev/null || true
}
trap collect EXIT

echo "::group::Install the real APK and the test APK"
( cd android && ./gradlew --no-daemon installDebug installDebugAndroidTest )
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell "rm -rf ${EVIDENCE}" 2>/dev/null || true
echo "::endgroup::"

.github/scripts/cross-platform-e2e.sh "$OUT"
