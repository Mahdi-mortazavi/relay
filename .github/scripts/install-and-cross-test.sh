#!/usr/bin/env bash
# Installs the real APK on the booted emulator, then hands over to the
# cross-platform leg (host client ↔ live phone).
#
# Usage: install-and-cross-test.sh <output-directory>
set -euo pipefail

OUT="${1:?usage: install-and-cross-test.sh <output-directory>}"
PKG=io.relay.app
EVIDENCE="files/e2e"

mkdir -p "$OUT"

collect() {
  if adb exec-out run-as "$PKG" tar c "$EVIDENCE" 2>/dev/null > "${OUT}/evidence.tar"; then
    tar xf "${OUT}/evidence.tar" -C "$OUT" --strip-components=1 2>/dev/null || true
  fi
  rm -f "${OUT}/evidence.tar"
  # See device-tests.sh: adb blocks forever once the emulator is gone, so the
  # log has to be pulled while the device is still alive.
  timeout 120 adb logcat -d > "${OUT}/logcat.txt" 2>/dev/null || true
}
trap collect EXIT

echo "::group::Install the real APK and the test APK"
( cd android && ./gradlew --no-daemon -PrelayTestAbis=true installDebug installDebugAndroidTest )
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell run-as "$PKG" rm -rf "$EVIDENCE" 2>/dev/null || true
echo "::endgroup::"

.github/scripts/cross-platform-e2e.sh "$OUT"
