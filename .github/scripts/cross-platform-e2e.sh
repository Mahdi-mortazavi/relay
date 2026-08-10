#!/usr/bin/env bash
# The cross-platform leg of the E2E device lab.
#
# A real Relay APK is running on a real Android emulator. This script asks it to
# hold a sharing session open, then runs the *Windows-side* client code
# (windows/Relay.E2E.Tests, built from the shipping Relay.Core) against it over
# an adb tunnel: the same decoder the Windows app uses reads the phone's real QR
# payload, and real HTTP traffic is pushed through the phone's SOCKS5 server.
#
# Usage: cross-platform-e2e.sh <output-directory>
set -euo pipefail

OUT="${1:?usage: cross-platform-e2e.sh <output-directory>}"
PKG=io.relay.app
TEST_PKG="${PKG}.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
EVIDENCE="/sdcard/Android/data/${PKG}/files/e2e"
HOST_PORT=11080

mkdir -p "$OUT"

device_has() { adb shell "[ -f '$1' ] && echo yes" 2>/dev/null | tr -d '\r' | grep -q yes; }

# The device test blocks on this marker. Always release it, even when the host
# side fails, so the emulator job reports the real failure instead of a timeout.
release_device() {
  adb shell "touch ${EVIDENCE}/host-done" >/dev/null 2>&1 || true
}
trap release_device EXIT

echo "::group::Start the phone's sharing session"
adb shell "rm -f ${EVIDENCE}/ready ${EVIDENCE}/host-done" >/dev/null 2>&1 || true
adb shell am instrument -w \
  -e annotation io.relay.app.e2e.HostHarness \
  "${TEST_PKG}/${RUNNER}" > "${OUT}/device-harness.log" 2>&1 &
INSTRUMENTATION_PID=$!

echo "Waiting for the phone to advertise..."
for _ in $(seq 1 120); do
  if device_has "${EVIDENCE}/ready"; then break; fi
  if ! kill -0 "$INSTRUMENTATION_PID" 2>/dev/null; then
    echo "The device harness exited before advertising:"
    cat "${OUT}/device-harness.log"
    exit 1
  fi
  sleep 1
done

if ! device_has "${EVIDENCE}/ready"; then
  echo "The phone never reached Advertising within 120s."
  cat "${OUT}/device-harness.log"
  exit 1
fi

ENDPOINT="$(adb shell cat "${EVIDENCE}/ready" | tr -d '\r')"
DEVICE_PORT="${ENDPOINT##*:}"
echo "Phone is advertising on ${ENDPOINT}"
echo "::endgroup::"

echo "::group::Bridge the host to the phone"
adb pull "${EVIDENCE}/pairing.json" "${OUT}/pairing.json"
adb forward "tcp:${HOST_PORT}" "tcp:${DEVICE_PORT}"
echo "adb forward localhost:${HOST_PORT} -> device:${DEVICE_PORT}"
echo "::endgroup::"

echo "::group::Windows-side client against the live phone"
set +e
RELAY_PAIRING_FILE="$(cd "$(dirname "${OUT}/pairing.json")" && pwd)/pairing.json" \
RELAY_SOCKS_PORT="${HOST_PORT}" \
RELAY_HOST_ALIAS="10.0.2.2" \
  dotnet test windows/Relay.E2E.Tests/Relay.E2E.Tests.csproj \
    --configuration Release \
    --logger "trx;LogFileName=cross-platform.trx" \
    --results-directory "${OUT}"
HOST_RESULT=$?
set -e
echo "::endgroup::"

# Let the device test finish its own post-conditions and report.
release_device
trap - EXIT
wait "$INSTRUMENTATION_PID" || true
adb forward --remove "tcp:${HOST_PORT}" >/dev/null 2>&1 || true

echo "::group::Device harness result"
cat "${OUT}/device-harness.log"
echo "::endgroup::"

if ! grep -q "^OK (" "${OUT}/device-harness.log"; then
  echo "FAIL: the device side of the cross-platform test did not pass."
  exit 1
fi
if [ "$HOST_RESULT" -ne 0 ]; then
  echo "FAIL: the host side of the cross-platform test did not pass."
  exit "$HOST_RESULT"
fi
echo "PASS: real traffic crossed from the host client through the phone and back."
