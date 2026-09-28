param([string]$RepositoryPath = (Join-Path $PSScriptRoot '..'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'nooagram-release.ps1')
$script:checks = 0
function Assert-Release([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}
function Assert-ReleaseThrows([scriptblock]$Action, [string]$Message) {
    $threw = $false
    try { & $Action | Out-Null } catch { $threw = $true }
    Assert-Release $threw $Message
}

$repoPath = (Resolve-Path -LiteralPath $RepositoryPath).Path
$source = Get-Content -LiteralPath (Join-Path $repoPath 'nooagram-version.json') -Raw | ConvertFrom-Json
$local = Get-NooagramVersion -RepositoryPath $repoPath
$again = Get-NooagramVersion -RepositoryPath $repoPath
$ci = Get-NooagramVersion -RepositoryPath $repoPath -RunNumber 5
$next = Get-NooagramVersion -RepositoryPath $repoPath -RunNumber 6
Assert-Release ($local.VersionCode -ge 125000043) 'Audit APK must upgrade versionCode 125000042.'
Assert-Release ($local.VersionName -ceq $again.VersionName) 'Version metadata must be deterministic.'
Assert-Release ($local.VersionName -ceq "$($source.version)-$($local.AppVersion).$($source.upstream_tag -replace '\.', '-')+$($local.VersionCode)") 'Visible version must use the short app/upstream/code format.'
Assert-Release ($local.UpstreamCommit -ceq $source.upstream_commit) 'Build must use the pinned upstream commit.'
Assert-Release ($ci.VersionCode -eq $local.VersionCode + 5) 'CI must advance from the local version-code floor.'
Assert-Release ($next.VersionCode -gt $ci.VersionCode -and $next.VersionName -cne $ci.VersionName) 'Next delivery needs a new code and name.'
$override = Get-NooagramVersion -RepositoryPath $repoPath -VersionCode ($local.VersionCode + 20)
Assert-Release ($override.VersionCode -eq $local.VersionCode + 20) 'Local code override was ignored.'
Assert-ReleaseThrows { Get-NooagramVersion -RepositoryPath $repoPath -VersionCode 125000042 } 'Old local code must be rejected.'
Assert-ReleaseThrows { Get-NooagramVersion -RepositoryPath $repoPath -VersionCode 2100000001 } 'Android version-code overflow must be rejected.'
Assert-ReleaseThrows { Get-NooagramVersion -RepositoryPath $repoPath -VersionCode $local.VersionCode -RunNumber 1 } 'Ambiguous code inputs must be rejected.'

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixture = Join-Path $tempRoot ('nooagram-release-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    $output = Join-Path $fixture 'apk'
    $destination = Join-Path $fixture 'release'
    New-Item -ItemType Directory -Path $output | Out-Null
    $abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64')
    $elements = @()
    foreach ($abi in $abis) {
        $name = "$abi.apk"
        [IO.File]::WriteAllBytes((Join-Path $output $name), [Text.Encoding]::UTF8.GetBytes("fixture-$abi"))
        $elements += [pscustomobject]@{
            filters = @([pscustomobject]@{ filterType = 'ABI'; value = $abi })
            versionName = $local.VersionName
            versionCode = $local.VersionCode
            outputFile = $name
        }
    }
    function Write-FixtureMetadata($Items) {
        @{ elements = @($Items) } | ConvertTo-Json -Depth 5 |
            Set-Content -LiteralPath (Join-Path $output 'output-metadata.json') -Encoding utf8
    }
    Write-FixtureMetadata $elements
    Export-NooagramReleaseAssets -OutputDirectory $output -Destination $destination `
        -Repository 'example/Nooagram' -Version $local -Timestamp 1234567890 -Changelog 'Audit fixes'
    $manifest = Get-Content -LiteralPath (Join-Path $destination 'update.json') -Raw | ConvertFrom-Json
    Assert-Release (@($manifest.assets.PSObject.Properties).Count -eq 3) 'Manifest must publish all three exact ABIs.'
    Assert-Release ($manifest.version_code -eq $local.VersionCode -and $manifest.version -ceq $local.VersionName) 'Manifest version differs from APK version.'
    Assert-Release ($manifest.commit -ceq $local.Commit -and $manifest.upstream_tag -ceq $local.UpstreamTag) 'Manifest provenance missing.'
    foreach ($abi in $abis) {
        $asset = $manifest.assets.$abi
        $path = Join-Path $destination "Nooagram-v$($local.VersionName)-$abi.apk"
        Assert-Release ($asset.size -eq (Get-Item -LiteralPath $path).Length) "Wrong $abi size."
        Assert-Release ($asset.sha256 -ceq (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()) "Wrong $abi checksum."
        Assert-Release ($asset.download_url -clike 'https://github.com/example/Nooagram/releases/download/*' -and $asset.download_url.Contains('%2B')) "Wrong $abi release URL."
    }
    Assert-Release ($manifest.download_url -ceq $manifest.assets.'arm64-v8a'.download_url) 'Legacy ARM64 URL must match its exact ABI entry.'
    Assert-Release ($manifest.sha256_32 -ceq $manifest.assets.'armeabi-v7a'.sha256) 'Legacy ARMv7 hash must match its exact ABI entry.'
    Assert-Release (@(Get-Content -LiteralPath (Join-Path $destination 'SHA256SUMS.txt')).Count -eq 3) 'Checksums must cover all APKs.'
    Write-FixtureMetadata @($elements[0], $elements[1])
    Assert-ReleaseThrows { Get-NooagramApk -OutputDirectory $output -Abi x86_64 -Version $local } 'Missing x86_64 must fail instead of selecting ARM.'
    Write-FixtureMetadata @($elements[0], $elements[0], $elements[1], $elements[2])
    Assert-ReleaseThrows { Get-NooagramApk -OutputDirectory $output -Abi arm64-v8a -Version $local } 'Duplicate ABI outputs must fail.'
    $elements[0].versionName = 'stale-version'
    Write-FixtureMetadata $elements
    Assert-ReleaseThrows { Get-NooagramApk -OutputDirectory $output -Abi arm64-v8a -Version $local } 'Stale APK version must fail.'
    $elements[0].versionName = $local.VersionName
    $elements[0].versionCode = $local.VersionCode - 1
    Write-FixtureMetadata $elements
    Assert-ReleaseThrows { Get-NooagramApk -OutputDirectory $output -Abi arm64-v8a -Version $local } 'Stale APK code must fail.'
    $elements[0].versionCode = $local.VersionCode
    $elements[0].outputFile = '../outside.apk'
    Write-FixtureMetadata $elements
    Assert-ReleaseThrows { Get-NooagramApk -OutputDirectory $output -Abi arm64-v8a -Version $local } 'APK path outside output directory must fail.'
} finally {
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    $expected = [IO.Path]::GetFullPath($fixture)
    if ($resolved -ne $expected -or [IO.Path]::GetDirectoryName($resolved).TrimEnd('\', '/') -ne $tempRoot.TrimEnd('\', '/')) {
        throw 'Refusing to clean an unexpected test fixture path.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Host "$script:checks Nooagram release checks passed."
