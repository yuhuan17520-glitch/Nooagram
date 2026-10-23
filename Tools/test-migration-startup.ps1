param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    [string]$AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk",
    [ValidateRange(1,5)][int]$Attempts = 3
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
# Deliberately fixed: this test must never stop, clear or uninstall production.
$package = 'fork.yuhuan.nooagram.migrationtest'
$component = "$package/org.telegram.messenger.DefaultIcon"
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null
$evidence = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
$results = @()
for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    & $adb -s $Serial shell am force-stop $package
    if ($LASTEXITCODE -ne 0) { throw 'Failed to stop migration test package' }
    $since = (& $adb -s $Serial shell "date '+%m-%d %H:%M:%S.000'").Trim()
    $launch = @(& $adb -s $Serial shell am start -W -n $component 2>&1)
    $launch | Set-Content -LiteralPath (Join-Path $evidence "launch-$attempt.txt") -Encoding utf8
    if ($LASTEXITCODE -ne 0 -or ($launch -join ' ') -notmatch 'Status: ok') { throw "Launch request failed: $launch" }
    Start-Sleep -Seconds 10
    $appPid = ((& $adb -s $Serial shell pidof $package) -join '').Trim()
    if ($appPid -notmatch '^\d+$') { throw "Test package did not survive launch attempt $attempt" }
    $logs = @(& $adb -s $Serial logcat -d --pid=$appPid -T $since -v threadtime 2>&1)
    $logs | Set-Content -LiteralPath (Join-Path $evidence "app-$attempt.log") -Encoding utf8
    if (($logs -join ' ') -match 'FATAL EXCEPTION|JNI_ERR|签名校验失败|Fatal signal') { throw "Application error on attempt $attempt; inspect app log" }
    if (($logs -join ' ') -notmatch '签名一致') { throw 'Native certificate verification success was not observed' }
    $resumed = @(& $adb -s $Serial shell dumpsys activity activities | Select-String 'mResumedActivity|topResumedActivity' | ForEach-Object { $_.Line })
    if (($resumed -join ' ') -notmatch [regex]::Escape($package)) { throw 'Migration app is not in the foreground; unlock the device and retry' }
    $resumed | Set-Content -LiteralPath (Join-Path $evidence "resumed-$attempt.txt") -Encoding utf8
    $results += [ordered]@{attempt=$attempt; pid=$appPid; survived_seconds=10; native_certificate_verified=$true; foreground=$true}
    Write-Output "PASS cold start $attempt, PID $appPid, native certificate verified, foreground after 10s"
}
[ordered]@{package=$package; serial=$Serial; timestamp=(Get-Date -Format o); results=$results} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'startup-results.json') -Encoding utf8
# UI appearance must still be inspected; no login or message actions are performed.
