param(
    [string]$TvHost = "192.168.0.98:5555",
    [string]$Mode = "parallel",
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$baseAndroid = "$env:LOCALAPPDATA\Android"
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    if (Test-Path "$baseAndroid\jdk-17\bin\java.exe") {
        $env:JAVA_HOME = "$baseAndroid\jdk-17"
    }
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$baseAndroid\Sdk" }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

if (-not (Test-Path "local.properties")) {
    $escapedSdk = $sdk -replace '\\', '\\'
    "sdk.dir=$escapedSdk" | Out-File -Encoding ascii "local.properties"
}

if (-not $SkipBuild) {
    Write-Host "=== Building TV Maintenance Daemon Android App (Release APK) ==="
    .\gradlew.bat assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
}

$apkPath = "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath"
}
Write-Host "Built APK: $apkPath ($((Get-Item $apkPath).Length) bytes)"
