param([string]$OutputDirectory)

$ErrorActionPreference = 'Stop'
$mediaRepo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if (!$OutputDirectory) { $OutputDirectory = Join-Path $mediaRepo 'TMessagesProj\build\media-path-probes' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$junitJar = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\junit\junit\4.13.2') -Recurse -Filter 'junit-4.13.2.jar' | Select-Object -First 1
$hamcrestJar = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.hamcrest\hamcrest-core') -Recurse -Filter '*.jar' | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Select-Object -First 1
if (!$junitJar -or !$hamcrestJar) { throw 'JUnit dependencies must be resolved by Gradle first.' }
$mediaClasspath = @($OutputDirectory, $junitJar.FullName, $hamcrestJar.FullName) -join [IO.Path]::PathSeparator
& javac -encoding UTF-8 -cp $mediaClasspath -d $OutputDirectory (Join-Path $PSScriptRoot 'AyuMediaPathTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Media path probe compilation failed.' }
Push-Location -LiteralPath $mediaRepo
try {
    & java "-Djava.io.tmpdir=$OutputDirectory" -cp $mediaClasspath org.junit.runner.JUnitCore com.radolyn.ayugram.utils.AyuMediaPathTest
    if ($LASTEXITCODE -ne 0) { throw 'Media path probes failed.' }
} finally { Pop-Location }
