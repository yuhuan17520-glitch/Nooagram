Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-NooagramVersion {
    param([Parameter(Mandatory)][string]$RepositoryPath)
    $repoPath = (Resolve-Path -LiteralPath $RepositoryPath).Path
    & (Join-Path $repoPath 'Tools/check-nagram-migration.ps1') -RequireReleaseReady | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Migration validation failed.' }
    Get-NooagramCandidateVersion -RepositoryPath $repoPath -Commit ((& git -C $repoPath rev-parse HEAD).Trim())
}

function Get-NooagramCandidateVersion {
    param(
        [Parameter(Mandatory)][string]$RepositoryPath,
        [string]$Commit
    )
    $repoPath = (Resolve-Path -LiteralPath $RepositoryPath).Path
    $source = Get-Content -LiteralPath (Join-Path $repoPath 'nooagram-version.json') -Raw | ConvertFrom-Json
    if ($source.version -cnotmatch '^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$') {
        throw 'Nooagram version must be major.minor.patch.'
    }
    if ([long]$source.version_code_floor -le [long]$source.previous_release_version_code -or
        [long]$source.previous_release_version_code -lt 125000081 -or
        [long]$source.version_code_floor -gt 2100000000) {
        throw 'Unsafe Android upgrade version floor.'
    }
    $appVersion = @(Get-Content -LiteralPath (Join-Path $repoPath 'gradle.properties') | Where-Object { $_ -cmatch '^APP_VERSION_NAME=' })[0].Substring('APP_VERSION_NAME='.Length).Trim()
    if ($appVersion -cnotmatch '^[0-9]+[.][0-9]+[.][0-9]+$') { throw 'Invalid Telegram version.' }
    if (!$Commit) {
        $Commit = (& git -C $repoPath rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve build commit.' }
    }
    if ($Commit -cnotmatch '^[0-9a-f]{40}$') { throw 'Invalid build commit.' }
    $code = [long]$source.version_code_floor
    $name = "$($source.version)-$appVersion.n$($source.upstream_build)+$code"
    [pscustomobject]@{
        Version = $source.version
        VersionName = $name
        VersionCode = $code
        # URL paths must be accepted by both the old and migrated updater.
        ReleaseTag = 'v' + $name.Replace('+', '-')
        UpstreamRepository = $source.upstream_repository
        UpstreamCommit = $source.upstream_commit
        Commit = $Commit
    }
}

function Get-NooagramReleaseNotesPath {
    param(
        [Parameter(Mandatory)][string]$RepositoryPath,
        [Parameter(Mandatory)][string]$Version
    )
    if ($Version -cnotmatch '^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$') {
        throw 'Nooagram version must be major.minor.patch.'
    }
    $repoPath = (Resolve-Path -LiteralPath $RepositoryPath).Path
    $releaseDirectory = [IO.Path]::GetFullPath((Join-Path $repoPath 'docs/releases'))
    $path = [IO.Path]::GetFullPath((Join-Path $releaseDirectory ($Version + '.md')))
    $prefix = $releaseDirectory.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (!$path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -or
        !(Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing release notes for version $Version."
    }
    return $path
}

function Get-NooagramReleaseNotes {
    param(
        [Parameter(Mandatory)][string]$RepositoryPath,
        [Parameter(Mandatory)][string]$Version
    )
    $path = Get-NooagramReleaseNotesPath -RepositoryPath $RepositoryPath -Version $Version
    $notes = Get-Content -LiteralPath $path -Raw
    if ([string]::IsNullOrWhiteSpace($notes)) { throw "Empty release notes for version $Version." }
    return $notes
}

function Export-NooagramReleaseAssets {
    param(
        [Parameter(Mandatory)][string]$OutputDirectory,
        [Parameter(Mandatory)][string]$Destination,
        [Parameter(Mandatory)]$Version,
        [Parameter(Mandatory)][long]$Timestamp,
        [string]$Changelog = ''
    )
    if ($Version.ReleaseTag -cnotmatch '^v[A-Za-z0-9][A-Za-z0-9._-]*$' -or
        $Version.Commit -cnotmatch '^[0-9a-f]{40}$' -or
        $Version.UpstreamCommit -cnotmatch '^[0-9a-f]{40}$' -or
        $Version.UpstreamRepository -cne 'NextAlone/Nagram' -or
        $Version.VersionCode -le 125000081 -or $Version.VersionCode -gt 2100000000 -or $Timestamp -le 0) {
        throw 'Invalid release metadata.'
    }
    $abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64')
    $apks = @{}
    $metadataFiles = @(Get-ChildItem -LiteralPath $OutputDirectory -Recurse -Filter output-metadata.json -File)
    foreach ($file in $metadataFiles) {
        $metadata = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
        if ($metadata.applicationId -cne 'fork.yuhuan.nooagram' -or $metadata.variantName -cne 'release') {
            throw 'Only production release APKs may be published; candidate/debug artifacts are rejected.'
        }
        foreach ($element in $metadata.elements) {
            $filters = @($element.filters)
            if ($filters.Count -ne 1 -or $filters[0].filterType -cne 'ABI' -or $filters[0].value -cnotin $abis) {
                throw 'Expected exactly one supported ABI filter.'
            }
            $abi = $filters[0].value
            if ($apks.ContainsKey($abi)) { throw "Duplicate APK for $abi." }
            if ($element.versionName -cne $Version.VersionName -or [long]$element.versionCode -ne $Version.VersionCode -or
                $element.outputFile -cmatch '[/\\:]' -or $element.outputFile -cnotmatch '^[^/\\:]+[.]apk$') {
                throw 'APK version or filename differs from release metadata.'
            }
            $apk = Get-Item -LiteralPath (Join-Path $file.DirectoryName $element.outputFile)
            if ($apk.Length -le 0) { throw 'Empty APK.' }
            $apks[$abi] = $apk
        }
    }
    if ($apks.Count -ne $abis.Count) { throw 'All three release architectures are required.' }
    if (Test-Path -LiteralPath $Destination) { throw 'Refusing to overwrite an existing asset directory.' }
    New-Item -ItemType Directory -Path $Destination | Out-Null
    $assets = [ordered]@{}
    $checksums = @()
    $baseUrl = "https://github.com/yuhuan17520-glitch/Nooagram/releases/download/$($Version.ReleaseTag)/"
    foreach ($abi in $abis) {
        $name = "Nooagram-$($Version.ReleaseTag)-$abi.apk"
        $path = Join-Path $Destination $name
        Copy-Item -LiteralPath $apks[$abi].FullName -Destination $path
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        $assets[$abi] = [ordered]@{ download_url = $baseUrl + $name; size = $apks[$abi].Length; sha256 = $hash }
        $checksums += "$hash  $name"
    }
    $manifest = [ordered]@{
        version = $Version.VersionName
        version_code = $Version.VersionCode
        upstream_repository = $Version.UpstreamRepository
        upstream_commit = $Version.UpstreamCommit
        commit = $Version.Commit
        timestamp = $Timestamp
        can_not_skip = $false
        changelog = $Changelog
        assets = $assets
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
