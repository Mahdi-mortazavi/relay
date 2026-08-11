#!/usr/bin/env bash
# Runs the Full Mode endpoint's own test suite on a real Android device.
#
# Usage: wg-device-test.sh <test-binary> <output-directory>
#
# The Go job already proves a WireGuard tunnel terminates and its traffic
# reaches the internet — on the runner, against glibc, on the runner's kernel.
# Android is a different libc (bionic), a different linker and a different
# sandbox, and this endpoint is the one piece of Relay that is nothing but
# system calls: raw UDP, a userspace network stack, and sockets dialled out of
# it. "It works on Linux" is not the claim anyone downloads the app for.
#
# So the same suite is cross-compiled for android/amd64 and run on the emulator.
# The test binary carries the tests with it; nothing here re-implements them.
set -euo pipefail

BINARY="${1:?usage: wg-device-test.sh <test-binary> <output-directory>}"
OUT="${2:?usage: wg-device-test.sh <test-binary> <output-directory>}"
REMOTE=/data/local/tmp/relay

mkdir -p "$OUT"

echo "::group::Device under test"
adb shell getprop ro.build.version.release | tr -d '\r' | sed 's/^/Android /'
adb shell getprop ro.product.cpu.abi | tr -d '\r' | sed 's/^/ABI /'
echo "::endgroup::"

echo "::group::Push the suite"
adb shell rm -rf "$REMOTE" >/dev/null 2>&1 || true
adb shell mkdir -p "${REMOTE}/wg" "${REMOTE}/shared"
adb push "$BINARY" "${REMOTE}/wg/relaywg.test"
# The config-contract test reads ../shared/test-vectors.json relative to its own
# directory, exactly as it does in the repo, so the layout is reproduced here
# rather than the test being taught a second way to find its vectors.
adb push shared/test-vectors.json "${REMOTE}/shared/test-vectors.json"
adb shell chmod 755 "${REMOTE}/wg/relaywg.test"
echo "::endgroup::"

echo "::group::Run it on the device"
set +e
# -test.v so the log names every test that ran: a suite that silently matched
# nothing exits 0 and looks identical to one that passed.
adb shell "cd ${REMOTE}/wg && ./relaywg.test -test.v -test.timeout 8m 2>&1" \
  | tr -d '\r' | tee "${OUT}/wg-device-test.log"
status=${PIPESTATUS[0]}
set -e
echo "::endgroup::"

# adb shell's exit status is the adb client's, not the remote command's, on
# older platform-tools — and a suite that crashed on the device would then look
# like a pass. Judge by what the runner printed instead.
if ! grep -q '^PASS$\|^ok ' "${OUT}/wg-device-test.log"; then
  echo "::error::The endpoint's suite did not pass on the device."
  exit 1
fi
if grep -q '^--- FAIL' "${OUT}/wg-device-test.log"; then
  echo "::error::Some endpoint tests failed on the device."
  exit 1
fi

ran=$(grep -c '^--- PASS' "${OUT}/wg-device-test.log" || true)
if [ "${ran:-0}" -lt 5 ]; then
  echo "::error::Only ${ran} tests ran on the device; the suite has more than that."
  exit 1
fi
echo "PASS: ${ran} endpoint tests passed on Android (adb status ${status})."
