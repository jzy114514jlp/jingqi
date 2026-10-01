param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $env:JAVA_HOME) {
    $localJdk = Get-ChildItem -LiteralPath "$projectRoot\.tools\jdk64" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
}
if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath "$projectRoot\.tools\sdk")) { $env:ANDROID_HOME = "$projectRoot\.tools\sdk" }
if (-not $env:JAVA_HOME) { throw 'JDK 17 is required. Set JAVA_HOME or use Android Studio.' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME = "$projectRoot\.tools\gradle-home"
$env:ANDROID_USER_HOME = "$projectRoot\.tools\android-user"
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
if (-not $SkipTests -and (Get-Command python -ErrorAction SilentlyContinue)) {
    & python "$projectRoot\scripts\prepare-test-cache.py"
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare verified test runtimes.' }
}
$tasks = @(':app:assembleDebug', ':demo:assembleDebug')
if (-not $SkipTests) { $tasks += @(':core:test', ':app:testDebugUnitTest', ':app:lintDebug', ':demo:lintDebug') }
& "$projectRoot\gradlew.bat" @tasks --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Build failed. See Gradle output above.' }
New-Item -ItemType Directory -Force -Path "$projectRoot\deliverables" | Out-Null
Copy-Item -LiteralPath "$projectRoot\app\build\outputs\apk\debug\app-debug.apk" -Destination "$projectRoot\deliverables\JingQi-debug.apk"
Copy-Item -LiteralPath "$projectRoot\demo\build\outputs\apk\debug\demo-debug.apk" -Destination "$projectRoot\deliverables\JingQi-Demo-debug.apk"
Write-Output 'APKs are ready in deliverables.'
