param()

$ErrorActionPreference = 'Stop'
$pinnedRepo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
& java '-Duser.language=en' (Join-Path $PSScriptRoot 'RegressionRunner.java') $pinnedRepo
if ($LASTEXITCODE -ne 0) { throw 'Pinned/settings regression probes found a failure.' }
