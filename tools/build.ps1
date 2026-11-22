param([switch]$Install)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$androidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_HOME = $androidSdk

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java was not found. Install JDK 17 and restart VS Code.'
}
if (-not (Test-Path (Join-Path $androidSdk 'platforms\android-35\android.jar'))) {
    throw "Android SDK Platform 35 was not found in '$androidSdk'. Install Android SDK Platform 35 through Android Studio's SDK Manager."
}

# The repository's Gradle wrapper pins and verifies the supported Gradle version.
$gradleExe = Join-Path $projectRoot 'gradlew.bat'
if (-not (Test-Path $gradleExe)) { throw 'Gradle wrapper is missing.' }

Push-Location $projectRoot
try {
    Write-Host 'Building APK...'
    & $gradleExe --no-daemon ':app:assembleDebug'
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit code $LASTEXITCODE)." }
    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) { throw 'Gradle reported success, but the APK was not found.' }
    Write-Host "APK ready: $apk"

    if ($Install) {
        $adbExe = Join-Path $androidSdk 'platform-tools\adb.exe'
        if (-not (Test-Path $adbExe)) { throw 'Android Platform-Tools (adb.exe) were not found in the SDK.' }
        Write-Host 'Checking connected Android devices...'
        & $adbExe devices
        if ($LASTEXITCODE -ne 0) { throw 'ADB device query failed.' }
        Write-Host 'Installing on connected AYN Thor...'
        & $adbExe install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. Check USB debugging authorization on the Thor.' }
    }
} finally {
    Pop-Location
}
