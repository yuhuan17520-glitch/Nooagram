function Get-NooagramVersion {
    param(
        [Parameter(Mandatory)][string]$RepositoryPath,
        [long]$RunNumber = 0,
        [long]$VersionCode = 0
    )

    $repoPath = (Resolve-Path -LiteralPath $RepositoryPath).Path
    $source = Get-Content -LiteralPath (Join-Path $repoPath 'nooagram-version.json') -Raw | ConvertFrom-Json
    $number = '(0|[1-9][0-9]*)'
    if ($source.version -cnotmatch "^$number\.$number\.$number$") {
        throw 'Nooagram version must be a major.minor.patch release.'
    }
    if ($source.upstream_tag -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$' -or
        $source.upstream_commit -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Invalid pinned upstream tag or commit.'
    }
    $upstreamCommit = & git -C $repoPath rev-parse --verify "refs/tags/$($source.upstream_tag)^{commit}"
    if ($LASTEXITCODE -ne 0 -or $upstreamCommit -cne $source.upstream_commit) {
        throw 'The pinned upstream tag is missing or no longer matches its recorded commit.'
    }
    & git -C $repoPath merge-base --is-ancestor $upstreamCommit HEAD
    if ($LASTEXITCODE -ne 0) {
        throw 'The pinned upstream release is not an ancestor of this build.'
    }
    $upstreamProperties = & git -C $repoPath show "${upstreamCommit}:gradle.properties"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read upstream version properties.' }
    $upstreamAppVersion = @($upstreamProperties | Where-Object { $_ -cmatch '^APP_VERSION_NAME=' })
    $localAppVersion = @(Get-Content -LiteralPath (Join-Path $repoPath 'gradle.properties') |
        Where-Object { $_ -cmatch '^APP_VERSION_NAME=' })
    if ($upstreamAppVersion.Count -ne 1 -or $localAppVersion.Count -ne 1 -or
        $upstreamAppVersion[0] -cne $localAppVersion[0]) {
        throw 'APP_VERSION_NAME must match the pinned upstream release.'
    }
    $appVersion = $localAppVersion[0].Substring('APP_VERSION_NAME='.Length).Trim()
    if ($appVersion -cnotmatch "^$number\.$number\.$number$") {
        throw 'APP_VERSION_NAME must be a major.minor.patch version.'
    }
    $commit = & git -C $repoPath rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve build commit.' }
    $floor = [long]$source.version_code_floor
    if ($floor -lt 125000043 -or $RunNumber -lt 0 -or $VersionCode -lt 0 -or
        ($RunNumber -gt 0 -and $VersionCode -gt 0)) {
        throw 'Invalid version-code floor, run number or local override.'
    }
    # CI advances from the same floor used for local APKs, including after a rebase.
    $code = if ($VersionCode -gt 0) { $VersionCode } else { $floor + $RunNumber }
    if ($code -lt $floor -or $code -gt 2100000000) {
        throw 'Version code must be at least the release floor and fit the Android limit.'
    }
    $upstreamIdentifier = $source.upstream_tag -replace '\.', '-'
    $name = "$($source.version)-$appVersion.$upstreamIdentifier+$code"
    [pscustomobject]@{
        VersionName = $name
        VersionCode = $code
        AppVersion = $appVersion
        UpstreamTag = $source.upstream_tag
        UpstreamCommit = $upstreamCommit
        Commit = $commit
    }
}

function Get-NooagramApk {
    param(
        [Parameter(Mandatory)][string]$OutputDirectory,
        [Parameter(Mandatory)][string]$Abi,
        [Parameter(Mandatory)]$Version
    )

    $metadata = Get-Content -LiteralPath (Join-Path $OutputDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
    $matches = @($metadata.elements | Where-Object {
        $abiFilters = @($_.filters | Where-Object { $_.filterType -ceq 'ABI' })
        $abiFilters.Count -eq 1 -and $abiFilters[0].value -ceq $Abi
    })
    if ($matches.Count -ne 1) { throw "Expected exactly one APK for $Abi." }
    $element = $matches[0]
    if ($element.versionName -cne $Version.VersionName -or [long]$element.versionCode -ne $Version.VersionCode) {
        throw 'APK metadata does not match Nooagram version metadata. Wire NOOAGRAM_VERSION_NAME and NOOAGRAM_VERSION_CODE into build.gradle.'
    }
    if ([IO.Path]::GetFileName($element.outputFile) -cne $element.outputFile) {
        throw 'APK output must be a filename inside the output directory.'
    }
    $apk = Get-Item -LiteralPath (Join-Path $OutputDirectory $element.outputFile)
    if ($apk.Length -le 0) { throw "Empty APK for $Abi." }
    $apk
}

function Export-NooagramReleaseAssets {
    param(
        [Parameter(Mandatory)][string]$OutputDirectory,
        [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)]$Version,
        [Parameter(Mandatory)][long]$Timestamp,
        [string]$Changelog = ''
    )

    if ($Repository -cnotmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid GitHub repository.' }
    $abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64')
    # Resolve and validate every split before copying or writing a manifest.
    $apks = @{}
    foreach ($abi in $abis) {
        $apks[$abi] = Get-NooagramApk -OutputDirectory $OutputDirectory -Abi $abi -Version $Version
    }
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    $baseUrl = "https://github.com/$Repository/releases/download/$([Uri]::EscapeDataString('v' + $Version.VersionName))/"
    $assets = [ordered]@{}
    $checksums = @()
    foreach ($abi in $abis) {
        $name = "Nooagram-v$($Version.VersionName)-$abi.apk"
        $path = Join-Path $Destination $name
        Copy-Item -LiteralPath $apks[$abi].FullName -Destination $path
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        $assets[$abi] = [ordered]@{
            download_url = $baseUrl + [Uri]::EscapeDataString($name)
            size = $apks[$abi].Length
            sha256 = $hash
        }
        $checksums += "$hash  $name"
    }
    $manifest = [ordered]@{
        version = $Version.VersionName
        version_code = $Version.VersionCode
        upstream_tag = $Version.UpstreamTag
        upstream_commit = $Version.UpstreamCommit
        commit = $Version.Commit
        timestamp = $Timestamp
        can_not_skip = $false
        changelog = $Changelog
        assets = $assets
        # Older ARM clients still read these fields during the transition.
        download_url = $assets['arm64-v8a'].download_url
        size = $assets['arm64-v8a'].size
        sha256 = $assets['arm64-v8a'].sha256
        download_url_32 = $assets['armeabi-v7a'].download_url
        size_32 = $assets['armeabi-v7a'].size
        sha256_32 = $assets['armeabi-v7a'].sha256
    }
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $Destination 'update.json') -Encoding utf8
    $checksums | Set-Content -LiteralPath (Join-Path $Destination 'SHA256SUMS.txt') -Encoding ascii
}
