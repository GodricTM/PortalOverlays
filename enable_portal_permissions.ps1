param(
    [switch]$AccessibilityOnly
)

$ErrorActionPreference = "Continue"
$Package = "com.portal.overlays"
$AccessibilityService = "$Package/$Package.NavAccessibilityService"
$NotificationListener = "$Package/$Package.NotifyListenerService"

function Step($label, $action) {
    Write-Host ""
    Write-Host "-> $label" -ForegroundColor Cyan
    & $action
    if ($LASTEXITCODE -ne 0) {
        Write-Host "   FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
        return $false
    }
    return $true
}

function Get-Status {
    param($serial)
    $alerts   = (& adb -s $serial shell appops get $Package 2>$null) -match 'SYSTEM_ALERT_WINDOW:\s*allow'
    $a11ySet  = (& adb -s $serial shell settings get secure enabled_accessibility_services 2>$null) -like "*$AccessibilityService*"
    $a11yOn   = (& adb -s $serial shell settings get secure accessibility_enabled 2>$null).Trim() -eq '1'
    $a11yDump = (& adb -s $serial shell dumpsys accessibility 2>$null) -join "`n"
    $a11yBound = $a11yDump -match 'label=Overlays'
    $notifRaw = (& adb -s $serial shell settings get secure enabled_notification_listeners 2>$null) -join ''
    $notif    = $notifRaw -like "*$NotificationListener*"
    return [pscustomobject]@{
        Alert      = [bool]$alerts
        A11ySet    = [bool]$a11ySet
        A11yOn     = [bool]$a11yOn
        A11yBound  = [bool]$a11yBound
        A11y       = [bool]($a11ySet -and $a11yOn -and $a11yBound)
        Notif      = [bool]$notif
    }
}

function Show-Status($s, $label) {
    Write-Host ("   {0,-6} :  draw-over={1,-5}  a11y-set={2,-5}  a11y-bound={3,-5}  notif={4,-5}" -f `
        $label, $s.Alert.ToString().ToLower(), $s.A11ySet.ToString().ToLower(), `
        $s.A11yBound.ToString().ToLower(), $s.Notif.ToString().ToLower())
}

# ---- pre-flight ---------------------------------------------------------
Write-Host "Portal Overlays - permission grant" -ForegroundColor Yellow
Write-Host "Package: $Package"

# Check adb on PATH
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Host "adb not found on PATH. Install Android Platform Tools first." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Detecting connected Portal..."
& adb start-server 2>$null | Out-Null
$adbDevices = & adb devices 2>$null
Write-Host $adbDevices
# `adb devices` output looks like:
#   List of devices attached
#   818PGA02P1213011   device
# Pick the first line that ends in 'device' and isn't the header.
$serial = ($adbDevices | Select-String '^(\S+)\s+device\s*$' | ForEach-Object { $_.Matches[0].Groups[1].Value }) | Select-Object -First 1
if (-not $serial) {
    Write-Host "No Portal found. Plug in / enable ADB and try again." -ForegroundColor Red
    exit 1
}
Write-Host "Using serial: $serial"

$before = Get-Status $serial
Show-Status $before "before"

# ---- grant --------------------------------------------------------------
if (-not $AccessibilityOnly) {
    Step "Granting draw-over-apps" { & adb -s $serial shell appops set $Package SYSTEM_ALERT_WINDOW allow }
    Step "Allowing notification listener" { & adb -s $serial shell cmd notification allow_listener $NotificationListener }
}

Step "Writing accessibility service setting" {
    & adb -s $serial shell settings put secure enabled_accessibility_services $AccessibilityService
    & adb -s $serial shell settings put secure accessibility_enabled 1
}

# ---- verify -------------------------------------------------------------
Write-Host ""
Write-Host "Verifying..." -ForegroundColor Cyan
Start-Sleep -Seconds 3
$after = Get-Status $serial
Show-Status $after "after "

if ($after.Alert -and $after.A11y -and $after.Notif) {
    Write-Host ""
    Write-Host "All three permissions granted and accessibility service is bound." -ForegroundColor Green
    Write-Host "Open Portal Overlays and tap Overlays running." -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "Not everything took. Items still missing:" -ForegroundColor Red
    if (-not $after.Alert)     { Write-Host "  - SYSTEM_ALERT_WINDOW" }
    if (-not $after.A11ySet)   { Write-Host "  - enabled_accessibility_services setting" }
    if (-not $after.A11yOn)    { Write-Host "  - accessibility_enabled toggle" }
    if (-not $after.A11yBound) { Write-Host "  - accessibility service bound by AccessibilityServiceManager (dumpsys shows no 'Overlays' label)" }
    if (-not $after.Notif)     { Write-Host "  - notification listener" }
    Write-Host ""
    Write-Host "Things to try:" -ForegroundColor Yellow
    Write-Host "  1. The Portal AccessibilityServiceManager only picks up the setting once it has been initialised." -ForegroundColor Yellow
    Write-Host "     Reboot the Portal (Settings -> About -> Reboot, or: adb -s $serial reboot) and re-run this script." -ForegroundColor Yellow
    Write-Host "     The setting is wiped on every reboot, so the re-run is required even after a clean install." -ForegroundColor Yellow
    Write-Host "  2. Make sure no other tool is in the way (close the Portal Overlays app first)" -ForegroundColor Yellow
    Write-Host "  3. On the Portal: Settings -> Apps -> Portal Overlays -> enable all toggles by hand" -ForegroundColor Yellow
    Write-Host "  4. Re-run with -AccessibilityOnly to skip the draw-over / notif steps" -ForegroundColor Yellow
    exit 2
}
