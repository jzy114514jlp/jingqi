$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $projectRoot '.tools\sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) {
    $adb = (Get-Command adb -ErrorAction Stop).Source
}
$devices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($devices.Count -ne 1) { throw 'Connect exactly one authorized Android device with USB debugging enabled.' }
foreach ($name in @('JingQi-debug.apk','JingQi-Demo-debug.apk')) {
    $apk = Join-Path $projectRoot "deliverables\$name"
    if (-not (Test-Path -LiteralPath $apk)) { throw "APK missing: $apk. Run scripts/build.ps1 first." }
    & $adb -s $devices[0] install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "Install failed: $name" }
}
& $adb -s $devices[0] shell am start -n cn.jingqi.guard/.ui.MainActivity
Write-Output 'Installed. Enable accessibility yourself after reading the in-app disclosure.'
