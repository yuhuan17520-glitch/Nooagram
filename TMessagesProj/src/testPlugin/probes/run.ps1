param(
    [string]$OutputDirectory,
    [string]$NdkDirectory
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
if (!$OutputDirectory) {
    $OutputDirectory = Join-Path ([IO.Path]::GetTempPath()) ('plugin-audit-' + [guid]::NewGuid().ToString('N'))
}
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
& java (Join-Path $PSScriptRoot 'PluginAuditProbe.java') $repo $output
if ($LASTEXITCODE -ne 0) { throw 'Plugin Java probes failed.' }

if ($NdkDirectory) {
    $bin = Join-Path $NdkDirectory 'toolchains/llvm/prebuilt/windows-x86_64/bin'
    $clang = Join-Path $bin 'clang.exe'
    $readelf = Join-Path $bin 'llvm-readelf.exe'
    $task = Get-Content -Raw -LiteralPath (Join-Path $repo 'buildSrc/src/main/kotlin/org/telegram/chaquopy/ChaquopyTasks.kt')
    $linkFlags = @([regex]::Matches($task, '"(-Wl,-z,(?:max|common)-page-size=\d+)"') | ForEach-Object { $_.Groups[1].Value })
    if ($linkFlags.Count -ne 2) { throw 'Expected two explicit page-alignment options.' }
    foreach ($target in @('aarch64-linux-android27', 'armv7a-linux-androideabi27')) {
        $library = Join-Path $output ($target + '-alignment.so')
        'int alignment_probe(void) { return 39; }' | & $clang "--target=$target" -shared -fPIC -nostdlib -x c - @linkFlags -o $library
        if ($LASTEXITCODE -ne 0) { throw "Alignment probe link failed: $target" }
        $headers = & $readelf -lW $library
        if ($LASTEXITCODE -ne 0) { throw "ELF inspection failed: $target" }
        $loads = @($headers | Where-Object { $_ -match '^\s*LOAD\s' })
        if (!$loads.Count) { throw "ELF has no LOAD segments: $target" }
        foreach ($load in $loads) {
            $alignment = [Convert]::ToInt64(($load.Trim() -split '\s+')[-1], 16)
            if ($alignment -lt 16384) { throw "ELF LOAD alignment below 16 KB: $load" }
        }
        Write-Output "PASS: $target ELF LOAD segments aligned to at least 16 KB ($($loads.Count) segments)"
    }
}
Write-Output "Probe artifacts: $output"
