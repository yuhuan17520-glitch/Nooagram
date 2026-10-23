$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'nooagram-release.ps1')
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('nooagram-release-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $sandbox | Out-Null
$version = [pscustomobject]@{
    VersionName = '1.3.0-13.0.2.n1255+125001000'
    VersionCode = 125001000
    ReleaseTag = 'v1.3.0-13.0.2.n1255-125001000'
    UpstreamRepository = 'NextAlone/Nagram'
    UpstreamCommit = '87863c8a57854c739e1452e56591c07ea52415d3'
    Commit = '2e747e10c05045d5e86a4ba1fe91ebc8e18e85cc'
}
function Assert-Release($Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
}
function New-TestInputs([string]$Name, [string]$Mutation = '') {
    $root = Join-Path $sandbox $Name
    foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
        if ($Mutation -eq 'missing' -and $abi -eq 'x86_64') { continue }
        $dir = Join-Path $root $abi
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        $element = [ordered]@{
            filters = @(@{filterType = 'ABI'; value = $abi})
            versionName = $version.VersionName
            versionCode = $version.VersionCode
            outputFile = 'test.apk'
        }
        if ($Mutation -eq 'version') { $element.versionCode-- }
        if ($Mutation -eq 'traversal') { $element.outputFile = '../test.apk' }
        if ($Mutation -eq 'windows-traversal') { $element.outputFile = '..	est.apk' }
        [IO.File]::WriteAllBytes((Join-Path $dir 'test.apk'), [Text.Encoding]::UTF8.GetBytes($abi))
        $metadata = [ordered]@{
            applicationId = 'fork.yuhuan.nooagram'
            variantName = 'release'
            elements = @($element)
        }
        if ($Mutation -eq 'candidate') { $metadata.variantName = 'candidate' }
        if ($Mutation -eq 'debug') { $metadata.variantName = 'debug' }
        if ($Mutation -eq 'package') { $metadata.applicationId += '.migrationtest' }
        $metadata | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $dir 'output-metadata.json') -Encoding utf8
    }
    if ($Mutation -eq 'duplicate') {
        Copy-Item -LiteralPath (Join-Path $root 'arm64-v8a') -Destination (Join-Path $root 'duplicate') -Recurse
    }
    $root
}
function Expect-Rejection([string]$Mutation) {
    $inputs = New-TestInputs $Mutation $Mutation
    $destination = Join-Path $sandbox ($Mutation + '-assets')
    $rejected = $false
    try { Export-NooagramReleaseAssets -OutputDirectory $inputs -Destination $destination -Version $version -Timestamp 1792702800 }
    catch { $rejected = $true }
    Assert-Release $rejected "Expected rejection: $Mutation"
    Assert-Release (!(Test-Path -LiteralPath $destination)) "Invalid inputs left public assets: $Mutation"
    Write-Output "PASS rejected $Mutation"
}
$inputs = New-TestInputs 'positive'
$assets = Join-Path $sandbox 'assets'
Export-NooagramReleaseAssets -OutputDirectory $inputs -Destination $assets -Version $version -Timestamp 1792702800 -Changelog '中文 migration'
$manifest = Get-Content -LiteralPath (Join-Path $assets 'update.json') -Raw | ConvertFrom-Json
Assert-Release ($manifest.version -ceq $version.VersionName -and $manifest.version_code -eq $version.VersionCode) 'Wrong update version'
Assert-Release (@($manifest.assets.PSObject.Properties).Count -eq 3) 'Missing architecture'
Assert-Release ($manifest.download_url -ceq $manifest.assets.'arm64-v8a'.download_url) 'Legacy ARM64 URL differs'
Assert-Release ($manifest.download_url_32 -ceq $manifest.assets.'armeabi-v7a'.download_url) 'Legacy ARMv7 URL differs'
Assert-Release ($manifest.changelog -ceq '中文 migration') 'UTF-8 changelog damaged'
foreach ($asset in $manifest.assets.PSObject.Properties) {
    $uri = [Uri]$asset.Value.download_url
    Assert-Release ($uri.AbsoluteUri -cnotmatch '%' -and $uri.Scheme -ceq 'https' -and $uri.Host -ceq 'github.com') 'Updater rejects generated URL'
    Assert-Release ($uri.AbsolutePath.Split('/').Count -eq 7) 'Invalid release URL segments'
    $apk = Get-Item -LiteralPath (Join-Path $assets ([IO.Path]::GetFileName($uri.AbsolutePath)))
    Assert-Release ($apk.Length -eq $asset.Value.size) 'Wrong size'
    Assert-Release ((Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $asset.Value.sha256) 'Wrong checksum'
}
Assert-Release (@(Get-Content -LiteralPath (Join-Path $assets 'SHA256SUMS.txt')).Count -eq 3) 'Wrong checksum count'
Write-Output 'PASS three architectures, legacy update fields, safe URLs, UTF-8 and checksums'
foreach ($mutation in @('missing', 'duplicate', 'version', 'traversal', 'windows-traversal', 'candidate', 'debug', 'package')) { Expect-Rejection $mutation }
$rejected = $false
try { Export-NooagramReleaseAssets -OutputDirectory $inputs -Destination $assets -Version $version -Timestamp 1792702800 }
catch { $rejected = $true }
Assert-Release $rejected 'Existing asset directory was overwritten'
$version.ReleaseTag = 'v1.3.0%2B125001000'
$rejected = $false
try { Export-NooagramReleaseAssets -OutputDirectory $inputs -Destination (Join-Path $sandbox 'unsafe-tag') -Version $version -Timestamp 1792702800 }
catch { $rejected = $true }
Assert-Release $rejected 'URL-encoded tag was accepted'
Write-Output 'PASS refuses overwrite and encoded tag'
# Exercise version preparation against a disposable, shared history clone. Only
# the fixture metadata is marked ready; never change the working repository gate.
$fixtureRepo = Join-Path $sandbox 'version-repo'
$sourceRepo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
& git clone --shared --no-checkout --quiet $sourceRepo $fixtureRepo
if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated version fixture' }
New-Item -ItemType Directory -Path (Join-Path $fixtureRepo 'Tools') | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'check-nagram-migration.ps1') -Destination (Join-Path $fixtureRepo 'Tools')
Copy-Item -LiteralPath (Join-Path $sourceRepo 'gradle.properties') -Destination $fixtureRepo
$metadata = Get-Content -LiteralPath (Join-Path $sourceRepo 'nooagram-version.json') -Raw | ConvertFrom-Json
$metadata.migration_ready = $false
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixtureRepo 'nooagram-version.json') -Encoding utf8
$rejected = $false
try { Get-NooagramVersion -RepositoryPath $fixtureRepo | Out-Null }
catch {
    if ($_.Exception.Message -notlike '*not release-ready*') { throw }
    $rejected = $true
}
Assert-Release $rejected 'Unvalidated migration accepted'
$metadata.migration_ready = $true
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixtureRepo 'nooagram-version.json') -Encoding utf8
$actualVersion = Get-NooagramVersion -RepositoryPath $fixtureRepo
$telegramVersion = (Get-Content (Join-Path $fixtureRepo 'gradle.properties') | Where-Object { $_ -cmatch '^APP_VERSION_NAME=' }).Split('=')[1].Trim()
$expectedName = '{0}-{1}.n{2}+{3}' -f $metadata.version, $telegramVersion, $metadata.upstream_build, $metadata.version_code_floor
Assert-Release ($actualVersion.VersionName -ceq $expectedName) 'Build version name differs'
Assert-Release ($actualVersion.ReleaseTag -ceq ('v' + $expectedName.Replace('+', '-'))) 'Unsafe release tag'
Assert-Release ($actualVersion.VersionCode -eq $metadata.version_code_floor) 'Unexpected version code'
Write-Output 'PASS readiness guard and pinned release version in disposable fixture'
# Keep this small generated fixture directory in OS temp for inspection.
