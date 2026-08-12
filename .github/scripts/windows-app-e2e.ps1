<#
.SYNOPSIS
  The Windows half of the device lab: exercise the artifact a stranger actually
  downloads, on a machine that has never seen Relay.

  install (silent) -> verify layout -> launch -> verify it survives
  -> verify it left the system proxy alone while idle
  -> --restore-proxy -> uninstall (silent) -> verify nothing is left behind

  The system proxy assertions matter more than they look: Relay's most dangerous
  failure mode is leaving a dead SOCKS proxy in HKCU, which breaks every app on
  the machine. Every step here reads the real registry back.
#>
param(
    [Parameter(Mandatory = $true)][string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null

$InternetSettings = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$UninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{7E2F0D4B-9A64-4E5D-B1C4-52A18D6A2C11}_is1'
$results = New-Object System.Collections.ArrayList

function Record($name, $status, $detail) {
    $line = "{0,-6} {1} {2}" -f $status, $name, $detail
    Write-Host $line
    [void]$results.Add([pscustomobject]@{ check = $name; status = $status; detail = $detail })
    if ($status -eq 'FAIL') { $script:failed = $true }
}

function Get-ProxyState {
    $key = Get-ItemProperty -Path $InternetSettings -ErrorAction SilentlyContinue
    return [pscustomobject]@{
        Enable = if ($key) { $key.ProxyEnable } else { $null }
        Server = if ($key) { $key.ProxyServer } else { $null }
        Override = if ($key) { $key.ProxyOverride } else { $null }
        AutoConfigURL = if ($key) { $key.AutoConfigURL } else { $null }
    }
}

function Assert-ProxyUnchanged($baseline, $label) {
    $now = Get-ProxyState
    $same = ($baseline.Enable -eq $now.Enable) -and
            ($baseline.Server -eq $now.Server) -and
            ($baseline.Override -eq $now.Override) -and
            ($baseline.AutoConfigURL -eq $now.AutoConfigURL)
    if ($same) {
        Record $label 'PASS' 'system proxy identical to the pre-install snapshot'
    } else {
        Record $label 'FAIL' ("proxy changed: was Enable=$($baseline.Enable) Server='$($baseline.Server)'; " +
            "now Enable=$($now.Enable) Server='$($now.Server)'")
    }
}

function Save-Screenshot($name) {
    try {
        Add-Type -AssemblyName System.Windows.Forms, System.Drawing
        $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
        $bitmap.Save((Join-Path $EvidenceDir "$name.png"))
        $graphics.Dispose(); $bitmap.Dispose()
        Record "screenshot:$name" 'PASS' 'captured'
    } catch {
        # A GitHub Windows runner is not guaranteed an interactive desktop.
        Record "screenshot:$name" 'BLOCKED' "no capturable desktop session: $($_.Exception.Message)"
    }
}

$script:failed = $false
$baseline = Get-ProxyState
$baseline | ConvertTo-Json | Set-Content (Join-Path $EvidenceDir 'proxy-before.json')
Write-Host "Pre-install proxy: Enable=$($baseline.Enable) Server='$($baseline.Server)'"

# --- install ------------------------------------------------------------------

$setup = Get-ChildItem 'windows/installer/output' -Filter 'Relay-Setup-x64-*.exe' | Select-Object -First 1
if (-not $setup) { throw 'No installer was produced by the build step.' }
Record 'installer.built' 'PASS' $setup.Name

$install = Start-Process -FilePath $setup.FullName `
    -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/NOICONS' `
    -Wait -PassThru
if ($install.ExitCode -eq 0) {
    Record 'installer.silent-install' 'PASS' 'exit code 0'
} else {
    Record 'installer.silent-install' 'FAIL' "exit code $($install.ExitCode)"
}

$installDir = (Get-ItemProperty -Path $UninstallKey -ErrorAction SilentlyContinue).InstallLocation
if (-not $installDir) { $installDir = Join-Path $env:LOCALAPPDATA 'Programs\Relay' }
$installDir = $installDir.TrimEnd('\')
$exe = Join-Path $installDir 'Relay.App.exe'

if (Test-Path $exe) {
    Record 'installer.layout.exe' 'PASS' $exe
} else {
    Record 'installer.layout.exe' 'FAIL' "missing at $exe"
}
# resources.pri is the file whose absence made the window fail to load at
# runtime once already; a shipped installer without it is a broken product.
# relaywg-client.exe and wintun.dll are Full Mode. Without them the app still
# installs, still launches, still does Fast Mode -- and silently cannot do the
# mode its own UI offers. That is exactly how Full Mode spent four releases
# missing from every Android build, so the installed layout is checked for them
# rather than trusted. wintun.dll must sit beside the client specifically: it is
# loaded from the executable's own directory.
foreach ($required in @('resources.pri', 'Microsoft.WindowsAppRuntime.Bootstrap.dll',
                        'relaywg-client.exe', 'wintun.dll')) {
    if (Test-Path (Join-Path $installDir $required)) {
        Record "installer.layout.$required" 'PASS' 'present'
    } else {
        Record "installer.layout.$required" 'FAIL' 'missing from the installed app'
    }
}
Get-ChildItem $installDir -ErrorAction SilentlyContinue |
    Select-Object Name, Length |
    ConvertTo-Json | Set-Content (Join-Path $EvidenceDir 'installed-files.json')

Assert-ProxyUnchanged $baseline 'install.leaves-proxy-alone'

# --- launch -------------------------------------------------------------------

$logDir = Join-Path $env:LOCALAPPDATA 'Relay'
Remove-Item (Join-Path $logDir 'startup-error.log') -ErrorAction SilentlyContinue

$app = $null
if (Test-Path $exe) {
    $app = Start-Process -FilePath $exe -PassThru
    Start-Sleep -Seconds 20

    if ($app.HasExited) {
        Record 'app.launch' 'FAIL' "the app exited on its own with code $($app.ExitCode)"
    } else {
        Record 'app.launch' 'PASS' "still running after 20s (pid $($app.Id))"
    }
    Save-Screenshot 'windows-launched'

    $startupError = Join-Path $logDir 'startup-error.log'
    if (Test-Path $startupError) {
        Copy-Item $startupError (Join-Path $EvidenceDir 'startup-error.log')
        Record 'app.startup-clean' 'FAIL' (Get-Content $startupError -Raw)
    } else {
        Record 'app.startup-clean' 'PASS' 'no startup-error.log'
    }

    # Idle means idle: nothing is paired, so nothing may touch the system proxy.
    Assert-ProxyUnchanged $baseline 'app.idle-leaves-proxy-alone'

    Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
}

# --- the uninstall-time rollback entry point ----------------------------------

if (Test-Path $exe) {
    $restore = Start-Process -FilePath $exe -ArgumentList '--restore-proxy' -Wait -PassThru
    if ($restore.ExitCode -eq 0) {
        Record 'app.restore-proxy' 'PASS' 'exit code 0'
    } else {
        Record 'app.restore-proxy' 'FAIL' "exit code $($restore.ExitCode) -- uninstall relies on this path"
    }
    Assert-ProxyUnchanged $baseline 'restore-proxy.leaves-proxy-alone'
}

# --- uninstall ----------------------------------------------------------------

$uninstaller = Join-Path $installDir 'unins000.exe'
if (Test-Path $uninstaller) {
    Start-Process -FilePath $uninstaller `
        -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait
    # Inno's uninstaller relaunches itself from a temp copy, so -Wait can return
    # before the directory is actually gone.
    for ($i = 0; $i -lt 30 -and (Test-Path $exe); $i++) { Start-Sleep -Seconds 1 }

    if (Test-Path $exe) {
        Record 'uninstall.removes-app' 'FAIL' "$exe still present 30s after uninstall"
    } else {
        Record 'uninstall.removes-app' 'PASS' 'application files removed'
    }
    if (Get-ItemProperty -Path $UninstallKey -ErrorAction SilentlyContinue) {
        Record 'uninstall.removes-registration' 'FAIL' 'the uninstall registry entry survived'
    } else {
        Record 'uninstall.removes-registration' 'PASS' 'uninstall entry removed'
    }
    Assert-ProxyUnchanged $baseline 'uninstall.leaves-proxy-restored'
} else {
    Record 'uninstall.removes-app' 'FAIL' "no uninstaller at $uninstaller"
}

# --- report -------------------------------------------------------------------

$results | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $EvidenceDir 'windows-e2e-results.json')
$results | Format-Table -AutoSize | Out-String | Set-Content (Join-Path $EvidenceDir 'windows-e2e-results.txt')

$blocked = @($results | Where-Object { $_.status -eq 'BLOCKED' }).Count
Write-Host ''
Write-Host ("{0} passed, {1} failed, {2} blocked" -f `
    @($results | Where-Object { $_.status -eq 'PASS' }).Count,
    @($results | Where-Object { $_.status -eq 'FAIL' }).Count,
    $blocked)

if ($script:failed) {
    Write-Error 'Windows app E2E failed -- see the evidence artifact.'
    exit 1
}
