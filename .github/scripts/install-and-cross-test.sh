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

# Applies to *every* Gradle invocation below, not just the install: passing it
# with -P on one command and not the next made Gradle rebuild the APK arm64-only
# for the test task, and AGP then reported "0 of 1 connected devices compatible".
export ORG_GRADLE_PROJECT_relayTestAbis=true

collect() {
  echo "::group::Collect evidence"
  # File-by-file through run-as. `tar` is not dependable inside a run-as shell,
  # and when it failed the artifact came back with nothing and no explanation.
  local names
  # Plain argv, no `sh -c`: adb joins its arguments into one string for the
  # device shell, which would re-parse any redirect or quoting inside it.
  # Filtered to plain filenames. adb reports a failed `run-as` on stdout, so an
  # unfiltered listing once produced a "file" called
  # "run-as: unknown package: io.relay.app" — whose colons made the artifact
  # upload reject the whole job, turning a green test run red.
  names="$(adb exec-out run-as "$PKG" ls -1 files/e2e 2>/dev/null \
    | tr -d '\r' | grep -E '^[A-Za-z0-9._-]+$' || true)"
  if [ -z "$names" ]; then
    echo "No device evidence found. What the app data directory holds:"
    adb exec-out run-as "$PKG" ls -la files 2>&1 | head -20 || true
  else
    mkdir -p "${OUT}/device"
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      # exec-out, not shell: these are PNGs and must not be LF-translated.
      adb exec-out run-as "$PKG" cat "files/e2e/${name}" > "${OUT}/device/${name}" 2>/dev/null || true
    done <<< "$names"
  fi
  timeout 120 adb logcat -d > "${OUT}/logcat.txt" 2>/dev/null || echo "logcat unavailable"
  ls -R "$OUT" || true
  echo "::endgroup::"
}

trap collect EXIT

echo "::group::Install the real APK and the test APK"
( cd android && ./gradlew --no-daemon installDebug installDebugAndroidTest )
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell run-as "$PKG" rm -rf "$EVIDENCE" 2>/dev/null || true
echo "::endgroup::"

.github/scripts/cross-platform-e2e.sh "$OUT"
