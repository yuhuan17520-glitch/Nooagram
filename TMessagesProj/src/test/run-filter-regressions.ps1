param(
    [string]$RepositoryPath = (Join-Path $PSScriptRoot '..\..\..'),
    [string]$GradleCache = (Join-Path $env:USERPROFILE '.gradle\caches')
)

$ErrorActionPreference = 'Stop'
$filterRepo = (Resolve-Path -LiteralPath $RepositoryPath).Path
$filterOutput = Join-Path $filterRepo 'TMessagesProj\build\filter-audit-tests'
$filterClasses = Join-Path $filterOutput 'classes'
New-Item -ItemType Directory -Path $filterClasses -Force | Out-Null
$filterDependencies = @(
    (Join-Path $filterRepo 'TMessagesProj\build\intermediates\javac\normalDebug\compileNormalDebugJavaWithJavac\classes'),
    (Join-Path $filterRepo 'TMessagesProj\build\intermediates\built_in_kotlinc\normalDebug\compileNormalDebugKotlin\classes'),
    (Join-Path $filterRepo 'TMessagesProj\build\intermediates\javac\normalRelease\compileNormalReleaseJavaWithJavac\classes'),
    (Join-Path $filterRepo 'TMessagesProj\build\intermediates\built_in_kotlinc\normalRelease\compileNormalReleaseKotlin\classes'),
    (Join-Path $filterRepo 'TMessagesProj\build\intermediates\compile_and_runtime_r_class_jar\normalDebug\processNormalDebugResources\R.jar')
)
$filterDependencies += @(Get-ChildItem -LiteralPath (Join-Path $GradleCache '9.4.0\transforms') -Filter android.jar -Recurse |
    ForEach-Object { $_.FullName })
$filterDependencies += @(Get-ChildItem -LiteralPath (Join-Path $GradleCache 'modules-2\files-2.1') -Filter '*.jar' -Recurse |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | ForEach-Object { $_.FullName })
$filterDependencies += @(Get-ChildItem -LiteralPath (Join-Path $GradleCache '9.4.0\transforms') -Filter '*.jar' -Recurse |
    Where-Object { $_.Name -eq 'classes.jar' -or $_.Name -like '*-api.jar' } | ForEach-Object { $_.FullName })
$filterClasspath = ($filterDependencies -join ';').Replace('\', '/')
$filterSources = @(
    'TMessagesProj/src/main/java/com/radolyn/ayugram/database/FilterPrefsMigrator.java',
    'TMessagesProj/src/main/java/com/radolyn/ayugram/database/dao/RegexFilterDao.java',
    'TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters/AyuFilter.java',
    'TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters/AyuFilterCache.java',
    'TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters/RegexFiltersSettingActivity.java',
    'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/MessageHelper.java',
    'TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/NooagramQuickFilter.java',
    'TMessagesProj/src/test/java/com/radolyn/ayugram/database/dao/RegexFilterMergeTest.java',
    'TMessagesProj/src/test/java/com/radolyn/ayugram/database/FilterPrefsMigratorTest.java',
    'TMessagesProj/src/test/java/tw/nekomimi/nekogram/filters/AyuFilterCacheTest.java',
    'TMessagesProj/src/test/java/tw/nekomimi/nekogram/helpers/MessageFilterTextTest.java',
    'TMessagesProj/src/test/java/tw/nekomimi/nekogram/helpers/NooagramQuickFilterTest.java'
)
$filterArgs = @('-proc:none', '-encoding', 'UTF-8', '-d', ('"' + $filterClasses.Replace('\', '/') + '"'),
    '-classpath', ('"' + $filterClasspath + '"'))
$filterArgs += $filterSources | ForEach-Object { '"' + (Join-Path $filterRepo $_).Replace('\', '/') + '"' }
$filterCompileArgs = Join-Path $filterOutput 'javac.args'
[IO.File]::WriteAllLines($filterCompileArgs, $filterArgs, [Text.UTF8Encoding]::new($false))
& javac '-J-Duser.language=en' ('@' + $filterCompileArgs)
if ($LASTEXITCODE -ne 0) { throw 'Focused filter compilation failed' }

$filterRunArgs = Join-Path $filterOutput 'java.args'
[IO.File]::WriteAllLines($filterRunArgs, @('-cp', ('"' + $filterClasses.Replace('\', '/') + ';' + $filterClasspath + '"'),
    'org.junit.runner.JUnitCore',
    'com.radolyn.ayugram.database.dao.RegexFilterMergeTest',
    'com.radolyn.ayugram.database.FilterPrefsMigratorTest',
    'tw.nekomimi.nekogram.filters.AyuFilterCacheTest',
    'tw.nekomimi.nekogram.helpers.MessageFilterTextTest',
    'tw.nekomimi.nekogram.helpers.NooagramQuickFilterTest'), [Text.UTF8Encoding]::new($false))
& java ('@' + $filterRunArgs)
if ($LASTEXITCODE -ne 0) { throw 'Focused filter tests failed' }
