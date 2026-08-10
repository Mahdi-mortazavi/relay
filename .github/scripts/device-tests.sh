#!/usr/bin/env bash
# Runs the instrumented suite on the booted emulator and collects the evidence
# the tests wrote (screenshots of every state, the pairing payload, a journal).
#
# Usage: device-tests.sh <output-directory>
set -euo pipefail

OUT="${1:?usage: device-tests.sh <output-directory>}"
PKG=io.relay.app
EVIDENCE="/sdcard/Android/data/${PKG}/files/e2e"

mkdir -p "$OUT"

echo "::group::Device under test"
adb shell getprop ro.build.version.release | tr -d '\r' | sed 's/^/Android /'
adb shell getprop ro.build.version.sdk | tr -d '\r' | sed 's/^/API /'
echo "::endgroup::"

collect() {
  echo "::group::Collect evidence"
  adb pull "$EVIDENCE" "$OUT" >/dev/null 2>&1 || echo "no device evidence directory"
  # Inside the script, so the device is still up. Run after the emulator is torn
  # down and adb blocks forever on the stale offline device instead of failing.
  timeout 120 adb logcat -d > "${OUT}/logcat.txt" 2>/dev/null || echo "logcat unavailable"
  ls -R "$OUT" || true
  echo "::endgroup::"
}
trap collect EXIT

echo "::group::Install"
( cd android && ./gradlew --no-daemon installDebug installDebugAndroidTest )
# Pre-granted so the journey test measures the app, not the platform's
# permission dialog. Absent below API 33, where the grant simply fails.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell "rm -rf ${EVIDENCE}" 2>/dev/null || true
echo "::endgroup::"

echo "::group::Instrumented tests"
# The host-harness test needs the host side of the workflow driving it; it runs
# in the cross-platform job instead.
( cd android && ./gradlew --no-daemon connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notAnnotation=io.relay.app.e2e.HostHarness )
echo "::endgroup::"
