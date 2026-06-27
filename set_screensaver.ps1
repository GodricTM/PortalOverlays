<#
  Portal Overlays - set (or revert) the on-device screensaver.

  A floating overlay is hidden the instant a screen saver starts (Android draws the
  saver on top), so Portal Overlays ships its own screen saver - the NowPlayingDreamService -
  which re-hosts the now-playing card + clock and therefore stays on screen. This script
  registers it as the device screen saver.

  Usage:
    set_screensaver.bat            register Portal Overlays as the screen saver
    set_screensaver.bat -Revert    hand the screen saver back (re-enables Immortal if present)
    set_screensaver.bat -KeepImmortal   register, but do NOT touch the Immortal launcher

  The Immortal launcher (com.immortal.launcher), if installed, re-asserts ITS OWN screen
  saver on boot and on every return to its home screen, which would evict this one. That
  self-healing is a no-op without WRITE_SECURE_SETTINGS, so (unless -KeepImmortal) this
  script revokes that permission from Immortal. -Revert grants it back.
#>
param(
    [switch]$Revert,
    [switch]$KeepImmortal
)

$ErrorActionPreference = "Continue"
$Package              = "com.portal.overlays"
$DreamComponent       = "$Package/.NowPlayingDreamService"
$NotificationListener = "$Package/$Package.NotifyListenerService"
$ImmortalPkg          = "com.immortal.launcher"
$ImmortalPerm         = "android.permission.WRITE_SECURE_SETTINGS"

function Step($label, $action) {
    Write-Host ""
    Write-Host "-> $label" -ForegroundColor Cyan
    & $action | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "   FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
    } else {
        Write-Host "   ok" -ForegroundColor DarkGray
    }
}

# ---- pre-flight ---------------------------------------------------------
Write-Host "Portal Overlays - screensaver setup" -ForegroundColor Yellow
Write-Host "Dream: $DreamComponent"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Host "adb not found on PATH. Install Android Platform Tools first." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Detecting connected Portal..."
& adb start-server 2>$null | Out-Null
$adbDevices = & adb devices 2>$null
Write-Host $adbDevices
# Pick the first line that ends in 'device' and isn't the header.
$serial = ($adbDevices | Select-String '^(\S+)\s+device\s*$' | ForEach-Object { $_.Matches[0].Groups[1].Value }) | Select-Object -First 1
if (-not $serial) {
    Write-Host "No Portal found. Plug in / enable ADB and try again." -ForegroundColor Red
    exit 1
}
Write-Host "Using serial: $serial"

# Is the app even installed?
$installed = (& adb -s $serial shell pm list packages $Package 2>$null) -match [regex]::Escape($Package)
if (-not $installed) {
    Write-Host "Portal Overlays ($Package) is not installed on this Portal. Install it first." -ForegroundColor Red
    exit 1
}
# Is Immortal present?
$hasImmortal = [bool]((& adb -s $serial shell pm list packages $ImmortalPkg 2>$null) -match [regex]::Escape($ImmortalPkg))

# ===================== REVERT =====================
if ($Revert) {
    Write-Host ""
    Write-Host "Reverting screensaver..." -ForegroundColor Yellow
    if ($hasImmortal) {
        Step "Restoring Immortal secure-settings access" { & adb -s $serial shell pm grant $ImmortalPkg $ImmortalPerm; $global:LASTEXITCODE = 0 }
        Write-Host ""
        Write-Host "Done. Return to the Immortal home screen and it will re-assert its own screen saver." -ForegroundColor Green
    } else {
        Step "Disabling the screen saver" { & adb -s $serial shell settings put secure screensaver_enabled 0 }
        Write-Host ""
        Write-Host "Done. The Portal Overlays screen saver is turned off." -ForegroundColor Green
    }
    exit 0
}

# ===================== SET =====================
if ($hasImmortal -and -not $KeepImmortal) {
    Step "Stopping Immortal from reclaiming the screen saver" {
        & adb -s $serial shell pm revoke $ImmortalPkg $ImmortalPerm; $global:LASTEXITCODE = 0
    }
    Write-Host "   (revoked $ImmortalPerm from Immortal - undo later with: set_screensaver.bat -Revert)" -ForegroundColor DarkYellow
} elseif ($hasImmortal -and $KeepImmortal) {
    Write-Host ""
    Write-Host "Note: Immortal is installed and -KeepImmortal was passed, so it will keep reclaiming the" -ForegroundColor DarkYellow
    Write-Host "screen saver on boot / return-home. Run without -KeepImmortal to make this one stick." -ForegroundColor DarkYellow
}

# The now-playing card reads media sessions through the notification listener - allow it
# so the card works even if the main permission script was never run.
Step "Allowing notification listener (now-playing card)" { & adb -s $serial shell cmd notification allow_listener $NotificationListener; $global:LASTEXITCODE = 0 }
Step "Registering the screen saver" { & adb -s $serial shell settings put secure screensaver_components $DreamComponent }
Step "Enabling the screen saver" { & adb -s $serial shell settings put secure screensaver_enabled 1 }

# ---- verify -------------------------------------------------------------
Write-Host ""
Write-Host "Verifying..." -ForegroundColor Cyan
$current = (& adb -s $serial shell settings get secure screensaver_components 2>$null).Trim()
$enabled = (& adb -s $serial shell settings get secure screensaver_enabled 2>$null).Trim()
Write-Host "   screensaver_components : $current"
Write-Host "   screensaver_enabled    : $enabled"

if ($current -eq $DreamComponent -and $enabled -eq "1") {
    Write-Host ""
    Write-Host "Portal Overlays is now the device screen saver." -ForegroundColor Green
    Write-Host "Configure the background (Black / Photo / Web page) on the app's Screensaver tab." -ForegroundColor Green
    Write-Host "Test it now without waiting for idle:" -ForegroundColor Green
    Write-Host "   adb -s $serial shell am start -n com.android.systemui/.Somnambulator" -ForegroundColor Gray
} else {
    Write-Host ""
    Write-Host "It didn't take." -ForegroundColor Red
    if ($current -ne $DreamComponent) {
        Write-Host "  screensaver_components is '$current', expected '$DreamComponent'." -ForegroundColor Red
        if ($hasImmortal) {
            Write-Host "  Immortal likely reclaimed it. Close the Immortal app, then re-run this script." -ForegroundColor Yellow
        }
    }
    if ($enabled -ne "1") { Write-Host "  screensaver_enabled is '$enabled', expected '1'." -ForegroundColor Red }
    exit 2
}
