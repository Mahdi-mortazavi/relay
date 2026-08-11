# Find out why an Android install failed, on the phone in front of you.
#
# "App not installed" is the only thing Android tells most people, and it
# covers several unrelated causes. This asks the phone directly and names the
# one that applies, with the fix.
#
# Run it with the phone plugged in and USB debugging enabled:
#
#   powershell -ExecutionPolicy Bypass -File scripts\diagnose-install.ps1
#
# It changes nothing on the phone unless you pass -Fix, which uninstalls the
# copy that is already there before installing.
#
# Written for Windows PowerShell 5.1, the one that ships with Windows: no
# null-conditionals, no ternaries, and ASCII only, because 5.1 reads a
# BOM-less .ps1 as ANSI and would mangle anything else.

[CmdletBinding()]
param(
    # An APK to test. Downloads the latest universal release when omitted.
    [string]$Apk,
    # Uninstall an existing copy first when that is what is blocking the install.
    [switch]$Fix
)

# Deliberately not 'Stop'. adb writes ordinary progress to stderr, and with
# 'Stop' plus the 2>&1 further down PowerShell turns that into a terminating
# error -- so the script would die on a failed install, which is the exact
# moment it exists to explain. Every call below is checked by hand instead.
$ErrorActionPreference = 'Continue'
$Package = 'io.relay.app'

function Say([string]$Text, [string]$Colour = 'Gray') { Write-Host $Text -ForegroundColor $Colour }
function Ok  ([string]$Text) { Say "  [ok]  $Text" 'Green' }
function Bad ([string]$Text) { Say "  [!!]  $Text" 'Red' }
function Info([string]$Text) { Say "  ---   $Text" 'DarkGray' }

Say ''
Say 'Relay install diagnosis' 'Cyan'
Say '=======================' 'Cyan'

# --------------------------------------------------------------------- adb
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adb = $null
if ($adbCommand) { $adb = $adbCommand.Source }
if (-not $adb) {
    foreach ($candidate in @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ProgramFiles\Android\platform-tools\adb.exe",
        "$env:USERPROFILE\platform-tools\adb.exe",
        "$env:USERPROFILE\Downloads\platform-tools\adb.exe")) {
        if (Test-Path $candidate) { $adb = $candidate; break }
    }
}
if (-not $adb) {
    Bad 'adb was not found.'
    Info 'Download "SDK Platform-Tools" from'
    Info '  https://developer.android.com/tools/releases/platform-tools'
    Info 'unzip it, and run this from that folder (or add it to PATH).'
    exit 2
}
Ok "adb: $adb"

# ------------------------------------------------------------------ device
$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
$ready   = $devices | Where-Object { $_ -match '\sdevice$' }
if (-not $ready) {
    Bad 'No phone is connected and authorised.'
    if ($devices | Where-Object { $_ -match 'unauthorized' }) {
        Info 'The phone is plugged in but has not been trusted. Unlock it and'
        Info 'accept the "Allow USB debugging?" prompt, then run this again.'
    } else {
        Info 'Enable Developer options (tap Build number seven times), turn on'
        Info 'USB debugging, and connect the cable.'
    }
    exit 2
}
$serial = ($ready[0] -split '\s+')[0]

function Prop([string]$Name) {
    # A failed getprop returns nothing, and .Trim() on that throws -- which
    # would end the run with a PowerShell stack trace instead of an answer.
    $v = & $adb -s $serial shell getprop $Name 2>$null
    if ($null -eq $v) { return '' }
    return ([string]$v).Trim()
}
$release = Prop 'ro.build.version.release'
$sdk     = Prop 'ro.build.version.sdk'
$abis    = Prop 'ro.product.cpu.abilist'
$model   = Prop 'ro.product.model'
Ok "phone: $model - Android $release (API $sdk)"
Info "CPU: $abis"

$sdkNumber = 0
[void][int]::TryParse($sdk, [ref]$sdkNumber)
if ($sdkNumber -gt 0 -and $sdkNumber -lt 26) {
    Bad "Relay needs Android 8.0 (API 26). This phone is API $sdk."
    Info 'No build of Relay can install here. This is the whole answer.'
    exit 1
}

# --------------------------------------------------------------------- APK
if (-not $Apk) {
    $Apk = Join-Path $env:TEMP 'Relay-android-universal.apk'
    Say ''
    Say 'Downloading the latest universal APK...' 'Cyan'
    $url = 'https://github.com/Mahdi-mortazavi/relay/releases/latest/download/Relay-android-universal.apk'
    try {
        # 5.1 defaults to TLS 1.0, which GitHub refuses.
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $url -OutFile $Apk -UseBasicParsing -ErrorAction Stop
    } catch {
        Bad "Could not download the APK: $($_.Exception.Message)"
        Info 'Download it by hand from the releases page and pass it in:'
        Info '  diagnose-install.ps1 -Apk C:\path\to\Relay-android-universal.apk'
        exit 2
    }
}
if (-not (Test-Path $Apk)) { Bad "No such file: $Apk"; exit 2 }
$size = (Get-Item $Apk).Length
Ok ("apk: {0} ({1:N0} bytes)" -f (Split-Path $Apk -Leaf), $size)

# A truncated download is indistinguishable from a corrupt APK until you look.
if ($size -lt 1MB) {
    Bad 'That file is too small to be an APK - the download was interrupted.'
    Info 'Delete it and download it again.'
    exit 1
}

# ------------------------------------------------- what is already installed
Say ''
Say 'Checking what is already on the phone' 'Cyan'
$installed = (& $adb -s $serial shell pm list packages $Package 2>$null) -match $Package
if ($installed) {
    $match = & $adb -s $serial shell dumpsys package $Package 2>$null |
             Select-String -Pattern 'versionName=' | Select-Object -First 1
    $versionLine = 'version unknown'
    if ($match) { $versionLine = $match.ToString().Trim() }
    Bad "Relay is already installed - $versionLine"
    Info ''
    Info 'This is the most common cause of "App not installed": Android refuses'
    Info 'to replace an app unless the new file carries the same signing key,'
    Info 'and a copy built from source is signed with a different one.'
    if ($Fix) {
        Say ''
        Say 'Removing it (-Fix was passed)...' 'Yellow'
        & $adb -s $serial uninstall $Package 2>&1 | Out-Null
        Ok 'Removed.'
    } else {
        Info 'Re-run with -Fix to remove it and install cleanly.'
    }
} else {
    Ok 'No existing copy - a signature clash cannot be the cause.'
}

# ----------------------------------------------------------------- install
Say ''
Say 'Installing' 'Cyan'
$out = (& $adb -s $serial install -r $Apk 2>&1) -join "`n"

if ($out -match 'Success') {
    Ok 'Installed.'
    & $adb -s $serial shell monkey -p $Package -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
    Start-Sleep -Seconds 4
    # pidof is missing on some builds, so fall back to the process list.
    $alive = & $adb -s $serial shell pidof $Package 2>$null
    if (-not $alive) {
        $alive = & $adb -s $serial shell ps -A 2>$null | Select-String $Package
    }
    if ($alive) {
        Ok 'Launched and still running.'
    } else {
        Bad 'It installed but the process died on launch.'
        Info 'Crash log:'
        & $adb -s $serial logcat -d -b crash 2>$null | Select-Object -Last 30 | ForEach-Object { Info $_ }
        exit 1
    }
    Say ''
    Say 'Nothing is wrong with the download - it installs and runs here.' 'Green'
    exit 0
}

# ------------------------------------------------- name the failure exactly
$reason = ([regex]::Match($out, 'INSTALL_[A-Z_]+')).Value
$shown = $reason
if (-not $shown) { $shown = $out }
Bad "Install failed: $shown"
Say ''
Say 'What that means' 'Cyan'

switch -Regex ($reason) {
    'UPDATE_INCOMPATIBLE|ALREADY_EXISTS' {
        Info 'A copy is installed that was signed with a different key - almost'
        Info 'always one built from source. Android will not replace it.'
        Say  '  FIX: re-run this script with -Fix, or: adb uninstall io.relay.app' 'Yellow'
    }
    'VERIFICATION_FAILURE|VERIFICATION_TIMEOUT' {
        Info 'Play Protect blocked the install, not the file itself.'
        Say  '  FIX: Play Store -> profile -> Play Protect -> turn off scanning,' 'Yellow'
        Say  '       install, then turn it back on.' 'Yellow'
    }
    'NO_MATCHING_ABIS' {
        Info "This APK has no code for this phone's CPU ($abis)."
        Say  '  FIX: download Relay-android-universal.apk, which carries them all.' 'Yellow'
    }
    'INSUFFICIENT_STORAGE' {
        Info 'Not enough free space. Installing needs several times the file size.'
        Say  '  FIX: free up a few hundred MB and try again.' 'Yellow'
    }
    'PARSE_FAILED|INVALID_APK' {
        Info 'The file is not a valid APK - usually a truncated download, or the'
        Info '.aab bundle, which is not installable by design.'
        Say  '  FIX: download Relay-android-universal.apk again and check it against' 'Yellow'
        Say  '       SHA256SUMS.txt on the release page.' 'Yellow'
    }
    'OLDER_SDK' {
        Info "Relay needs Android 8.0; this phone is API $sdk."
        Say  '  FIX: none - the phone is too old for this app.' 'Yellow'
    }
    default {
        Info 'Not a failure this script knows about. The full output was:'
        $out -split "`n" | ForEach-Object { Info $_ }
        Info ''
        Info 'Please open an issue with these lines, the phone model and Android'
        Info 'version above - that is enough to identify it.'
    }
}
Say ''
Say 'More detail: docs/install-troubleshooting.md' 'DarkGray'
exit 1
