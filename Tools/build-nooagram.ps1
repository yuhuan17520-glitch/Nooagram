param(
    [ValidateSet("Release", "Debug")]
    [string]$BuildType = "Release",

    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64")]
    [string]$Abi = "arm64-v8a",

    [switch]$Install,
    [string]$Device,
    [switch]$SkipSubmodules,
    [int]$LocalVersionCode = 125000000,

    [string]$LocalPropertiesPath
)

$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gradlew = Join-Path $repo "gradlew.bat"
$keystore = Join-Path $repo "TMessagesProj\release.keystore"
$localKeystore = Join-Path $repo "..\nooagram-secrets\nooagram-release.keystore"

$propertiesFile = $null
if ($LocalPropertiesPath) {
    $propertiesFile = (Resolve-Path $LocalPropertiesPath).Path
} elseif (Test-Path (Join-Path $repo "local.properties")) {
    $propertiesFile = Join-Path $repo "local.properties"
} elseif (Test-Path (Join-Path $repo "..\nooagram-secrets\local.properties")) {
    $propertiesFile = Join-Path $repo "..\nooagram-secrets\local.properties"
} else {
    throw "local.properties not found. Put it in the repo root or pass -LocalPropertiesPath."
}

if (-not (Test-Path $keystore)) {
    throw "TMessagesProj/release.keystore not found."
}

if (-not (Test-Path $localKeystore)) {
    throw "Nooagram release keystore not found at $localKeystore."
}

if (-not $SkipSubmodules) {
    Write-Host "Updating Git submodules..." -ForegroundColor Cyan
    git -C $repo submodule update --init --recursive
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to update Git submodules."
    }
}

$sdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    "C:\Android\sdk",
    "$env:LOCALAPPDATA\Android\Sdk"
) | Where-Object { $_ -and (Test-Path $_) }

if ($sdkCandidates.Count -eq 0) {
    throw "Android SDK not found. Set ANDROID_HOME or install it under C:\Android\sdk."
}

$sdk = (Resolve-Path $sdkCandidates[0]).Path
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

$env:LOCAL_PROPERTIES = [Convert]::ToBase64String(
    [System.IO.File]::ReadAllBytes($propertiesFile)
)

$env:BUILD_TIMESTAMP = [string][System.DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$env:COMMIT_ID = (git -C $repo rev-parse HEAD).Trim()
$env:NATIVE_TARGET = $Abi
$env:NOOAGRAM_KEYSTORE = (Resolve-Path $localKeystore).Path
$env:NOOAGRAM_LOCAL_VERSION_CODE = [string]$LocalVersionCode

$task = "TMessagesProj:assembleNormal$BuildType"
Write-Host "Building $task for $Abi..." -ForegroundColor Cyan

& $gradlew $task -x uploadCrashlyticsMappingFileNormalRelease --build-cache
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed."
}

$outputDir = Join-Path $repo "TMessagesProj\build\outputs\apk\normal\$($BuildType.ToLowerInvariant())"
$apk = Get-ChildItem $outputDir -Recurse -Filter "*$Abi*.apk" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $apk) {
    throw "No $Abi APK found under $outputDir."
}

$localDir = Join-Path $repo "build-local"
New-Item -ItemType Directory -Force $localDir | Out-Null
$localApk = Join-Path $localDir "Nooagram-$($BuildType.ToLowerInvariant())-$Abi.apk"
Copy-Item $apk.FullName $localApk -Force

Write-Host ""
Write-Host "APK: $localApk" -ForegroundColor Green

if ($Install) {
    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    }

    if (-not $adb) {
        throw "adb not found."
    }

    $devices = & $adb devices | Select-String "device$"
    if ($devices.Count -eq 0) {
        throw "No connected Android device."
    }

    if ($Device) {
        & $adb -s $Device install -r -d $localApk
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to install on $Device."
        }
    } elseif ($devices.Count -eq 1) {
        & $adb install -r -d $localApk
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to install on the connected device."
        }
    } else {
        Write-Host "Multiple devices connected:" -ForegroundColor Yellow
        & $adb devices
        throw "Run again with -Device <serial>."
    }
}
