#!/usr/bin/env bash
# Proves the *universal* APK — the artifact published for devices the arm64
# split cannot reach — actually installs and works on a non-arm64 device.
#
# This deliberately tests the shipped, R8-minified release APK rather than a
# debug build. A missing keep rule only shows up once minification runs, and a
# debug build would prove nothing about the file users download.
#
# Nothing here goes through instrumentation: the app is driven through its real
# UI with uiautomator, exactly as a person would, because the point is to test
# the APK as published and not a test-instrumented variant of it.
#
# The traffic check is self-contained. A plain HTTP server runs on the CI
# runner; the request is pushed *into* the phone's SOCKS5 server over adb and
# has to come back out to that server via 10.0.2.2 (the emulator's alias for its
# host). If the bytes arrive, the proxy genuinely relayed them — no external
# network, nothing to be flaky about.
#
# Usage: universal-apk-e2e.sh <apk> <output-directory>
set -euo pipefail

APK="${1:?usage: universal-apk-e2e.sh <apk> <output-directory>}"
OUT="${2:?usage: universal-apk-e2e.sh <apk> <output-directory>}"
PKG=io.relay.app
HOST_PORT=11080
ECHO_PORT=8099

mkdir -p "$OUT"
RESULTS="${OUT}/universal-apk-results.txt"
: > "$RESULTS"

pass() { printf '%-42s PASS  %s\n' "$1" "${2:-}" | tee -a "$RESULTS"; }
fail() { printf '%-42s FAIL  %s\n' "$1" "${2:-}" | tee -a "$RESULTS"; FAILED=1; }
FAILED=0

cleanup() {
  [ -n "${SERVER_PID:-}" ] && kill "$SERVER_PID" 2>/dev/null || true
  adb forward --remove tcp:${HOST_PORT} >/dev/null 2>&1 || true
  # Collect logs from inside the emulator's lifetime; doing it after teardown
  # blocks forever (that hung this lab for 45 minutes once already).
  timeout 60 adb logcat -d > "${OUT}/universal-logcat.txt" 2>/dev/null || true
}
trap cleanup EXIT

echo "::group::What is actually in this APK"
ABIS="$(unzip -Z1 "$APK" 'lib/*' 2>/dev/null | cut -d/ -f2 | sort -u | tr '\n' ' ')"
echo "APK: $APK ($(stat -c%s "$APK") bytes)"
echo "ABIs packaged: ${ABIS:-none}"
for want in arm64-v8a armeabi-v7a x86 x86_64; do
  case " $ABIS " in
    *" $want "*) pass "apk.contains.$want" ;;
    *)           fail "apk.contains.$want" "missing from the universal APK" ;;
  esac
done
echo "::endgroup::"

echo "::group::Install on an x86_64 device"
# The whole reason this artifact exists: an arm64-only APK fails here with
# INSTALL_FAILED_NO_MATCHING_ABIS, which is the bug report we are preventing.
adb uninstall "$PKG" >/dev/null 2>&1 || true
if adb install -r "$APK" > "${OUT}/install.log" 2>&1; then
  pass "install.universal-apk" "on $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
else
  fail "install.universal-apk" "$(tail -2 "${OUT}/install.log" | tr '\n' ' ')"
  cat "${OUT}/install.log"
  exit 1
fi

# Which native ABI the platform actually chose. If the packaging were wrong this
# is where it shows up as an empty or arm64 value on an x86_64 device.
SELECTED="$(adb shell pm dump "$PKG" 2>/dev/null | tr -d '\r' | grep -m1 'primaryCpuAbi' | sed 's/.*=//' | xargs || true)"
echo "primaryCpuAbi = ${SELECTED:-<unset>}"
case "$SELECTED" in
  x86_64|x86) pass "install.selected-native-abi" "$SELECTED" ;;
  # Not every image reports it; absence is not evidence of failure, and the
  # traffic test below is the real proof either way.
  ""|null)    pass "install.selected-native-abi" "not reported by this image" ;;
  *)          fail "install.selected-native-abi" "chose $SELECTED on an x86_64 device" ;;
esac
echo "::endgroup::"

echo "::group::Launch it"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adb logcat -c || true
adb shell am start -n "${PKG}/.MainActivity" -W > "${OUT}/launch.log" 2>&1
sleep 6

if adb shell pidof "$PKG" | tr -d '\r' | grep -qE '[0-9]'; then
  pass "launch.process-alive"
else
  fail "launch.process-alive" "the app died on startup"
  adb logcat -d | tail -60
  exit 1
fi

# A release build that crashes on a missing R8 keep rule shows up right here.
if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: .*io\.relay" > "${OUT}/crashes.txt" 2>/dev/null; then
  fail "launch.no-crash" "$(head -3 "${OUT}/crashes.txt" | tr '\n' ' ')"
else
  pass "launch.no-crash"
fi
echo "::endgroup::"

echo "::group::Start sharing through the real UI"
# Find the button by its label rather than by coordinates, so a layout change
# moves the tap instead of silently missing it.
tap_by_text() {
  local label="$1" dump bounds x1 y1 x2 y2
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  dump="$(adb exec-out cat /sdcard/ui.xml 2>/dev/null | tr -d '\r')"
  printf '%s' "$dump" > "${OUT}/ui-dump.xml"
  bounds="$(printf '%s' "$dump" \
    | tr '>' '\n' \
    | grep -F "text=\"$label\"" \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | head -1 | grep -oE '[0-9]+' | tr '\n' ' ')" || true
  [ -z "$bounds" ] && return 1
  read -r x1 y1 x2 y2 <<< "$bounds"
  adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}

if tap_by_text "Start Sharing"; then
  pass "ui.tapped-start-sharing"
else
  fail "ui.tapped-start-sharing" "button not found in the UI dump"
  exit 1
fi
sleep 8
adb exec-out screencap -p > "${OUT}/universal-sharing.png" 2>/dev/null || true
echo "::endgroup::"

# The phone now asks before letting a computer use the proxy -- that prompt is
# what makes a two-digit pairing code safe (/shared/pairing-beacon.md). The
# connection below blocks in the SOCKS handshake until someone answers it, so
# something has to be the someone. Runs in the background because the tap has
# to happen while the transfer is waiting, not before it starts.
approve_when_asked() {
  local deadline=$(( SECONDS + 90 ))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if tap_by_text "Allow" 2>/dev/null; then
      echo "approved the client on the phone"
      return 0
    fi
    sleep 2
  done
  echo "no approval prompt appeared within 90s"
  return 1
}

echo "::group::Relay real traffic through it"
approve_when_asked &
APPROVER_PID=$!
# Self-contained target: served by this runner, reached back through the phone.
python3 -m http.server "$ECHO_PORT" --bind 0.0.0.0 --directory "$OUT" \
  > "${OUT}/http-server.log" 2>&1 &
SERVER_PID=$!
echo "relay-universal-ok" > "${OUT}/probe.txt"
sleep 2

DEVICE_PORT=""
for p in 1080 1081 10800; do
  adb forward --remove tcp:${HOST_PORT} >/dev/null 2>&1 || true
  adb forward tcp:${HOST_PORT} tcp:${p} >/dev/null 2>&1 || continue
  if curl -s --max-time 20 --socks5-hostname "127.0.0.1:${HOST_PORT}" \
       "http://10.0.2.2:${ECHO_PORT}/probe.txt" -o "${OUT}/relayed.txt" 2>/dev/null; then
    if grep -q 'relay-universal-ok' "${OUT}/relayed.txt" 2>/dev/null; then
      DEVICE_PORT="$p"; break
    fi
  fi
done

if [ -n "$DEVICE_PORT" ]; then
  pass "proxy.relays-real-http" "SOCKS5 on device port ${DEVICE_PORT}"
else
  fail "proxy.relays-real-http" "no candidate port relayed the request"
  echo "--- app log ---"; adb logcat -d | grep -i relay | tail -40 || true
fi
echo "::endgroup::"

echo
echo "===== universal APK results ====="
cat "$RESULTS"
[ "$FAILED" -eq 0 ] || { echo "One or more checks failed."; exit 1; }
echo "All checks passed."
