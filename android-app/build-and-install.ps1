param(
    [string]$TvHost = "",
    [string]$Flavor = "community",
    [string]$PersonalOverlayDir = "",
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

$flavorCap = $Flavor.Substring(0,1).ToUpper() + $Flavor.Substring(1)
$gradleTask = "assemble${flavorCap}Release"
$gradleArgs = @($gradleTask, "--console=plain")
if ($PersonalOverlayDir -and (Test-Path $PersonalOverlayDir)) {
    $gradleArgs += "-PpersonalOverlayDir=$PersonalOverlayDir"
}

if (-not $SkipBuild) {
    Write-Host "=== Building TV Optimizer Android App ($gradleTask) ==="
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
}

$apkPath = "app\build\outputs\apk\$Flavor\release\app-$Flavor-release.apk"
if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath"
}
Write-Host "Built APK: $apkPath ($((Get-Item $apkPath).Length) bytes)"
