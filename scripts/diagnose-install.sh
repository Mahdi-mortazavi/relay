#!/usr/bin/env bash
# Find out why an Android install failed, on the phone in front of you.
#
# "App not installed" is the only thing Android tells most people, and it
# covers several unrelated causes. This asks the phone directly and names the
# one that applies, with the fix.
#
# Run it with the phone plugged in and USB debugging enabled:
#
#   ./scripts/diagnose-install.sh            # downloads the latest universal APK
#   ./scripts/diagnose-install.sh app.apk    # or tests a file you already have
#
# It changes nothing on the phone unless you pass --fix, which uninstalls the
# copy that is already there before installing.
set -uo pipefail

PKG=io.relay.app
APK=""
FIX=0
for arg in "$@"; do
  case "$arg" in
    --fix) FIX=1 ;;
    -h|--help) sed -n '2,14p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *) APK="$arg" ;;
  esac
done

if [ -t 1 ]; then
  R=$'\e[31m'; G=$'\e[32m'; Y=$'\e[33m'; C=$'\e[36m'; D=$'\e[90m'; N=$'\e[0m'
else
  R=; G=; Y=; C=; D=; N=
fi
ok()   { printf '  %s[ok]%s  %s\n' "$G" "$N" "$1"; }
bad()  { printf '  %s[!!]%s  %s\n' "$R" "$N" "$1"; }
info() { printf '  %s---   %s%s\n' "$D" "$1" "$N"; }
head_() { printf '\n%s%s%s\n' "$C" "$1" "$N"; }

head_ 'Relay install diagnosis'

# --------------------------------------------------------------------- adb
ADB=$(command -v adb 2>/dev/null)
if [ -z "$ADB" ]; then
  for c in "$HOME/Android/Sdk/platform-tools/adb" "$HOME/Library/Android/sdk/platform-tools/adb" \
           /usr/lib/android-sdk/platform-tools/adb; do
    [ -x "$c" ] && { ADB="$c"; break; }
  done
fi
if [ -z "$ADB" ]; then
  bad 'adb was not found.'
  info 'Install it:  apt install adb   |   brew install android-platform-tools'
  info 'or unzip "SDK Platform-Tools" from developer.android.com/tools/releases/platform-tools'
  exit 2
fi
ok "adb: $ADB"

# ------------------------------------------------------------------ device
READY=$("$ADB" devices | tail -n +2 | awk '$2=="device"{print $1}' | head -1)
if [ -z "$READY" ]; then
  bad 'No phone is connected and authorised.'
  if "$ADB" devices | grep -q unauthorized; then
    info 'The phone is plugged in but not trusted yet. Unlock it and accept the'
    info '"Allow USB debugging?" prompt, then run this again.'
  else
    info 'Enable Developer options (tap Build number seven times), turn on USB'
    info 'debugging, and connect the cable.'
  fi
  exit 2
fi
REL=$("$ADB" -s "$READY" shell getprop ro.build.version.release | tr -d '\r')
SDK=$("$ADB" -s "$READY" shell getprop ro.build.version.sdk | tr -d '\r')
ABIS=$("$ADB" -s "$READY" shell getprop ro.product.cpu.abilist | tr -d '\r')
MODEL=$("$ADB" -s "$READY" shell getprop ro.product.model | tr -d '\r')
ok "phone: $MODEL — Android $REL (API $SDK)"
info "CPU: $ABIS"

if [ "$SDK" -lt 26 ] 2>/dev/null; then
  bad "Relay needs Android 8.0 (API 26). This phone is API $SDK."
  info 'No build of Relay can install here. This is the whole answer.'
  exit 1
fi

# --------------------------------------------------------------------- APK
if [ -z "$APK" ]; then
  APK="${TMPDIR:-/tmp}/Relay-android-universal.apk"
  head_ 'Downloading the latest universal APK...'
  curl -fsSL -o "$APK" \
    https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-universal.apk \
    || { bad 'Download failed.'; exit 2; }
fi
[ -f "$APK" ] || { bad "No such file: $APK"; exit 2; }
SIZE=$(wc -c < "$APK")
ok "apk: $(basename "$APK") ($(printf "%'d" "$SIZE" 2>/dev/null || echo "$SIZE") bytes)"
if [ "$SIZE" -lt 1000000 ]; then
  bad 'That file is too small to be an APK — the download was interrupted.'
  info 'Delete it and download it again.'
  exit 1
fi

# ------------------------------------------------- what is already installed
head_ 'Checking what is already on the phone'
if "$ADB" -s "$READY" shell pm list packages | tr -d '\r' | grep -qx "package:$PKG"; then
  VER=$("$ADB" -s "$READY" shell dumpsys package "$PKG" | tr -d '\r' \
        | grep -m1 'versionName=' | xargs)
  bad "Relay is already installed — $VER"
  info ''
  info 'This is the most common cause of "App not installed": Android refuses to'
  info 'replace an app unless the new file carries the same signing key, and a'
  info 'copy built from source is signed with a different one.'
  if [ "$FIX" = 1 ]; then
    printf '\n%sRemoving it (--fix was passed)...%s\n' "$Y" "$N"
    "$ADB" -s "$READY" uninstall "$PKG" >/dev/null 2>&1 && ok 'Removed.'
  else
    info 'Re-run with --fix to remove it and install cleanly.'
  fi
else
  ok 'No existing copy — a signature clash cannot be the cause.'
fi

# ----------------------------------------------------------------- install
head_ 'Installing'
OUT=$("$ADB" -s "$READY" install -r "$APK" 2>&1)

if grep -qi 'Success' <<<"$OUT"; then
  ok 'Installed.'
  "$ADB" -s "$READY" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 4
  if "$ADB" -s "$READY" shell pidof "$PKG" >/dev/null 2>&1; then
    ok 'Launched and still running.'
    printf '\n%sNothing is wrong with the download — it installs and runs here.%s\n' "$G" "$N"
    exit 0
  fi
  bad 'It installed but the process died on launch.'
  info 'Crash log:'
  "$ADB" -s "$READY" logcat -d -b crash | tail -30 | while read -r l; do info "$l"; done
  exit 1
fi

# ------------------------------------------------- name the failure exactly
REASON=$(grep -o 'INSTALL_[A-Z_]*' <<<"$OUT" | head -1)
bad "Install failed: ${REASON:-$OUT}"
head_ 'What that means'
case "$REASON" in
  *UPDATE_INCOMPATIBLE*|*ALREADY_EXISTS*)
    info 'A copy is installed that was signed with a different key — almost always'
    info 'one built from source. Android will not replace it.'
    printf '  %sFIX: re-run with --fix, or:  adb uninstall %s%s\n' "$Y" "$PKG" "$N" ;;
  *VERIFICATION_FAILURE*|*VERIFICATION_TIMEOUT*)
    info 'Play Protect blocked the install, not the file itself.'
    printf '  %sFIX: Play Store -> profile -> Play Protect -> turn off scanning,%s\n' "$Y" "$N"
    printf '  %s     install, then turn it back on.%s\n' "$Y" "$N" ;;
  *NO_MATCHING_ABIS*)
    info "This APK has no code for this phone's CPU ($ABIS)."
    printf '  %sFIX: download Relay-android-universal.apk, which carries them all.%s\n' "$Y" "$N" ;;
  *INSUFFICIENT_STORAGE*)
    info 'Not enough free space. Installing needs several times the file size.'
    printf '  %sFIX: free up a few hundred MB and try again.%s\n' "$Y" "$N" ;;
  *PARSE_FAILED*|*INVALID_APK*)
    info 'The file is not a valid APK — usually a truncated download, or the .aab'
    info 'bundle, which is not installable by design.'
    printf '  %sFIX: download the .apk again and check it against SHA256SUMS.txt.%s\n' "$Y" "$N" ;;
  *OLDER_SDK*)
    info "Relay needs Android 8.0; this phone is API $SDK."
    printf '  %sFIX: none — the phone is too old for this app.%s\n' "$Y" "$N" ;;
  *)
    info 'Not a failure this script knows about. The full output was:'
    while read -r l; do info "$l"; done <<<"$OUT"
    info ''
    info 'Please open an issue with these lines, the phone model and the Android'
    info 'version above — that is enough to identify it.' ;;
esac
printf '\n%sMore detail: docs/install-troubleshooting.md%s\n' "$D" "$N"
exit 1
