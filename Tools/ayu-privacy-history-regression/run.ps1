param([string]$SqliteJdbc)

$ErrorActionPreference = 'Stop'
$regressionRepo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if (-not $SqliteJdbc) {
    $jdbcCache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.xerial\sqlite-jdbc'
    $jdbcFile = Get-ChildItem -LiteralPath $jdbcCache -Recurse -Filter 'sqlite-jdbc-*.jar' |
        Select-Object -First 1
    if (-not $jdbcFile) {
        throw 'Pass -SqliteJdbc with an existing SQLite JDBC jar; this runner never downloads dependencies.'
    }
    $SqliteJdbc = $jdbcFile.FullName
}
& java (Join-Path $PSScriptRoot 'RegressionRunner.java') $regressionRepo $SqliteJdbc
if ($LASTEXITCODE -ne 0) { throw 'Ayu privacy/history regression checks failed.' }
