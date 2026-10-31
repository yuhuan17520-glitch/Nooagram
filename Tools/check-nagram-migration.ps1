param([switch]$RequireReleaseReady)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$meta = Get-Content -LiteralPath (Join-Path $repo 'nooagram-version.json') -Raw | ConvertFrom-Json
if ($meta.upstream_repository -cne 'NextAlone/Nagram' -or @('main', 'dev') -cnotcontains $meta.upstream_branch -or
    $meta.upstream_commit -cnotmatch '^[0-9a-f]{40}$') { throw 'Invalid Nagram upstream pin.' }
& git -C $repo cat-file -e "$($meta.upstream_commit)^{commit}"
if ($LASTEXITCODE -ne 0) { throw 'Pinned upstream commit is missing; fetch full history.' }
& git -C $repo merge-base --is-ancestor $meta.upstream_commit HEAD
if ($LASTEXITCODE -ne 0) { throw 'Pinned Nagram is not an ancestor; this is not a migrated branch.' }
$upstream = (& git -C $repo show "$($meta.upstream_commit):TMessagesProj/build.gradle") -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0 -or $upstream -notmatch 'def verCode = (\d+)' -or
    [int]$Matches[1] -ne $meta.upstream_build) { throw 'Pinned Nagram build differs from metadata.' }
$official = @(& git -C $repo show "$($meta.upstream_commit):gradle.properties" | Where-Object { $_ -cmatch '^APP_VERSION_NAME=' })
$local = @(Get-Content -LiteralPath (Join-Path $repo 'gradle.properties') | Where-Object { $_ -cmatch '^APP_VERSION_NAME=' })
if ($official.Count -ne 1 -or $local.Count -ne 1 -or $official[0] -cne $local[0]) { throw 'Official Telegram version is not pinned correctly.' }
if ($meta.version_code_floor -le $meta.previous_release_version_code -or
    $meta.previous_release_version_code -lt 125000081 -or $meta.version_code_floor -gt 2100000000) { throw 'Unsafe Android upgrade version floor.' }
if ($RequireReleaseReady -and !$meta.migration_ready) { throw 'Migration is not release-ready.' }
Write-Output "PASS Nagram $($meta.upstream_branch) pin $($meta.upstream_commit), build $($meta.upstream_build), upgrade floor $($meta.version_code_floor)"
