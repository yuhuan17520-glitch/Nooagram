param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [string]$AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk"
)
$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$signerName = if ($IsLinux -or $IsMacOS) { 'apksigner' } else { 'apksigner.bat' }
$signer = Join-Path $AndroidSdk "build-tools/36.0.0/$signerName"
$verification = @(& $signer verify --verbose --print-certs $apkPath 2>&1)
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $verification" }
$fingerprints = @($verification | Select-String '^Signer #1 certificate SHA-1 digest: ([0-9a-fA-F]{40})$')
if ($fingerprints.Count -ne 1) { throw 'Expected exactly one verified APK signing certificate' }
$sha1 = $fingerprints[0].Matches[0].Groups[1].Value.ToUpperInvariant()
if ($sha1 -eq '3A0F57FE06485D0B90D0ACD990E3A30328E3988D') { throw 'Migration test APK must not use the upstream signing identity' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $libraries = @($zip.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/libtmessages[.]49[.]so$' })
    if ($libraries.Count -eq 0) { throw 'APK has no tmessages native library' }
    foreach ($entry in $libraries) {
        $stream = $entry.Open()
        $buffer = [IO.MemoryStream]::new()
        try { $stream.CopyTo($buffer); $contents = [Text.Encoding]::ASCII.GetString($buffer.ToArray()) }
        finally { $stream.Dispose(); $buffer.Dispose() }
        if (!$contents.Contains($sha1)) { throw "Native signer does not match APK certificate: $($entry.FullName)" }
        if ($contents.Contains('3A0F57FE06485D0B90D0ACD990E3A30328E3988D')) { throw "Upstream-bound native library found: $($entry.FullName)" }
        Write-Output "PASS $($entry.FullName) is bound to verified APK certificate $sha1"
    }
} finally { $zip.Dispose() }
# This detects the original regression; it does not replace real device startup.
