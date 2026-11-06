# Connect to the MEmu (Xiaoyao) emulator on a FIXED adb port.
#
# The emulator's adb port is 127.0.0.1:21503 for instance index 0.
# MEmuHyper.exe listens on this port for the first VM and it does NOT
# drift across reboots as long as you only have one instance. This script
# pins that port and verifies the link, so you can always use one command.
#
# Usage:
#   pwsh -File scripts/connect-memu.ps1
#   pwsh -File scripts/connect-memu.ps1 -Port 127.0.0.1:21513   # 2nd instance
param(
    [string]$Port = '127.0.0.1:21503'
)

$ErrorActionPreference = 'Stop'

# Prefer the SDK adb; fall back to the MEmu-bundled one.
$sdkAdb  = 'D:\AI\dev\android-sdk\platform-tools\adb.exe'
$memuAdb = 'D:\Program Files\Microvirt\MEmu\adb.exe'
$adb = if (Test-Path $sdkAdb) { $sdkAdb } else { $memuAdb }

if (-not (Test-Path $adb)) {
    Write-Host "adb.exe not found in SDK or MEmu dir." -ForegroundColor Red
    exit 1
}

Write-Host "Connecting $Port ..." -ForegroundColor Cyan
& $adb connect $Port | Out-Null

$online = $false
for ($i = 0; $i -lt 20; $i++) {
    $devices = & $adb devices
    if ($devices -match [regex]::Escape($Port) + '\s+device') { $online = $true; break }
    Start-Sleep -Milliseconds 500
}

if (-not $online) {
    Write-Host "Failed to connect $Port. Make sure MEmu is running." -ForegroundColor Red
    exit 1
}

Write-Host "Connected: $Port" -ForegroundColor Green
Write-Host "--- display ---" -ForegroundColor Cyan
& $adb -s $Port shell wm size
& $adb -s $Port shell wm density
Write-Host "--- uimode (night) ---" -ForegroundColor Cyan
& $adb -s $Port shell cmd uimode night
