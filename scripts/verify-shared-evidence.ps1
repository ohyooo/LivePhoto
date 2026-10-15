param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('linuxX64', 'macosArm64', 'mingwX64')]
    [string]$Target
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
# Kotlin's compile output is an unpacked KLIB, whose directory need not have
# a .klib suffix. Pack the actual main compilation, never the test binary.
$manifests = @(Get-ChildItem "shared/build/classes/kotlin/$Target/main" -Recurse -File -Filter manifest |
    Where-Object { $_.FullName.Replace('\', '/').EndsWith("/main/klib/shared/default/manifest") })
if ($manifests.Count -ne 1) { throw 'Expected one compiled main KLIB manifest.' }
$manifest = @{}
Get-Content $manifests[0].FullName | ForEach-Object {
    $parts = $_.Split('=', 2)
    if ($parts.Count -eq 2) { $manifest[$parts[0]] = $parts[1] }
}
$konanTarget = @{ linuxX64 = 'linux_x64'; macosArm64 = 'macos_arm64'; mingwX64 = 'mingw_x64' }[$Target]
if ($manifest['builtins_platform'] -ne 'NATIVE' -or $manifest['native_targets'] -ne $konanTarget) {
    throw 'The compiled main library is not the requested Native target.'
}
$libraryRoot = $manifests[0].Directory.Parent.FullName
$packedDirectory = Join-Path (Get-Location) 'ci-output/native-core'
New-Item -ItemType Directory -Path $packedDirectory | Out-Null
$archive = Join-Path $packedDirectory 'shared.klib'
[IO.Compression.ZipFile]::CreateFromDirectory($libraryRoot, $archive, [IO.Compression.CompressionLevel]::Optimal, $false)
$libraries = @(Get-Item $archive)
if ($libraries[0].Length -le 0) { throw 'Empty packed Native library.' }
$reports = @(Get-ChildItem "shared/build/test-results/${Target}Test" -File -Filter 'TEST-*.xml')
if ($reports.Count -eq 0) { throw 'Missing executed Native test reports.' }
$tests = 0
$fixtureTests = 0
$mediaTests = 0
$mediaSuites = @{
    FfmpegIntegrationTest = 1; FfmpegFrameIntegrationTest = 3
    FfmpegReplaceIntegrationTest = 1; FfmpegCreateTrimIntegrationTest = 1
    FfmpegCreateReplacementIntegrationTest = 1; FfmpegAudioTrimIntegrationTest = 4
    FfmpegTrimIntegrationTest = 2; FfmpegRemuxIntegrationTest = 5
    FfmpegTranscodeIntegrationTest = 2
    HeifDecodePreservationIntegrationTest = 1; SamsungRemuxRepairIntegrationTest = 1
    AppleConvertIntegrationTest = 1
}
$seenMedia = @{}
foreach ($file in $reports) {
    [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
    $suite = $report.testsuite
    if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or
        [int]$suite.skipped -ne 0 -or @($suite.SelectNodes('testcase/skipped')).Count -ne 0) {
        throw "Native shared tests failed or skipped: $($file.Name)"
    }
    if ([int]$suite.tests -ne @($suite.SelectNodes('testcase')).Count) { throw 'Test case count mismatch.' }
    $tests += [int]$suite.tests
    if ($file.Name -match 'ProtocolCompatibilityFixtureTest') { $fixtureTests += [int]$suite.tests }
    foreach ($name in $mediaSuites.Keys) {
        if ($suite.name -eq "${Target}Test.livephoto.core.media.$name") {
            if ($seenMedia.ContainsKey($name) -or [int]$suite.tests -ne $mediaSuites[$name]) {
                throw "Duplicate or incomplete real media suite: $name"
            }
            $seenMedia[$name] = $true
            $mediaTests += [int]$suite.tests
        }
    }
}
if ($mediaTests -ne 23 -or $seenMedia.Count -ne $mediaSuites.Count) { throw 'Mandatory common real media regressions must all execute on Native.' }
$dispatch = @($reports | Where-Object { $_.Name -eq "TEST-${Target}Test.livephoto.core.media.MediaBackendDispatchTest.xml" })
if ($dispatch.Count -ne 1) { throw 'Missing/duplicate shared backend dispatch suite.' }
[xml]$dispatchXml = Get-Content $dispatch[0].FullName -Raw
if ([int]$dispatchXml.testsuite.tests -ne 6) { throw 'Backend dispatch safety regressions did not all execute.' }
foreach ($name in @('AppleHeifPortableFixtureTest', 'AppleOrdinaryExifPortableFixtureTest')) {
    $matching = @($reports | Where-Object { $_.Name -eq "TEST-${Target}Test.livephoto.core.compatibility.$name.xml" })
    if ($matching.Count -ne 1) { throw "Missing/duplicate shared portable fixture suite: $name" }
    [xml]$xml = Get-Content $matching[0].FullName -Raw
    if ([int]$xml.testsuite.tests -ne 1) { throw "Incomplete shared portable fixture suite: $name" }
}
if ($fixtureTests -ne 20) { throw 'Fixed protocol fixture regressions must all execute on Native.' }
$fixtureEvidence = 'shared/build/reports/protocol-compatibility/verified-captures.tsv'
if (-not (Test-Path $fixtureEvidence) -or @(Get-Content $fixtureEvidence | Where-Object { $_.Trim() }).Count -ne 19) {
    throw 'Missing whole-asset verification manifest.'
}
# All existing shared tests must execute, not a tiny Native-only smoke subset.
$expected = 0
Get-ChildItem shared/src/commonTest,shared/src/nativeTest -Recurse -File -Filter '*.kt' | ForEach-Object {
    $expected += [regex]::Matches((Get-Content $_.FullName -Raw), '@Test\b').Count
}
if ($expected -le 0 -or $tests -ne $expected) {
    throw "Incomplete Native test execution: $tests tests, $expected shared @Test declarations."
}
$log = Get-Content ci-output/native-core.log -Raw
if ($log -notmatch ":shared:${Target}Test(?:\s|$)" -or
    $log -match ":shared:${Target}Test (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)") {
    throw 'Native tests were not freshly executed.'
}
# Retain each former module's execution gate after consolidating test binaries.
$groups = @{ core = 'livephoto.core.'; files = 'livephoto.adapters.files.'; process = 'livephoto.adapters.process.' }
$groupTests = @{}
foreach ($group in $groups.Keys) {
    $prefix = $groups[$group]
    $actual = 0
    foreach ($file in $reports) {
        [xml]$xml = Get-Content $file.FullName -Raw
        if ($xml.testsuite.name.StartsWith("${Target}Test.$prefix")) { $actual += [int]$xml.testsuite.tests }
    }
    $declared = 0
    Get-ChildItem shared/src/commonTest,shared/src/nativeTest -Recurse -File -Filter '*.kt' | ForEach-Object {
        $source = Get-Content $_.FullName -Raw
        if ($source -match ('(?m)^package\s+' + [regex]::Escape($prefix.TrimEnd('.')) + '(?:\.|\s|$)')) {
            $declared += [regex]::Matches($source, '@Test\b').Count
        }
    }
    if ($actual -ne $declared -or $declared -le 0) { throw "Incomplete $group group: $actual / $declared" }
    $groupTests[$group] = $actual
}
$nativeMedia = @($reports | Where-Object { $_.Name -match 'NativeFfmpegTest.xml$' })
if ($nativeMedia.Count -ne 1) { throw 'Missing Native FFmpeg adapter tests.' }
[xml]$nativeMediaXml = Get-Content $nativeMedia[0].FullName -Raw
if ([int]$nativeMediaXml.testsuite.tests -ne 5) { throw 'Incomplete Native FFmpeg adapter tests.' }
$interopRoot = "shared/build/classes/kotlin/$Target/main/cinterop"
$manifests = @(Get-ChildItem $interopRoot -Recurse -File -Filter manifest |
    Where-Object { $_.Directory.Name -eq 'default' })
$name = $(if ($Target -eq 'mingwX64') { 'shared-win32.klib' } else { 'shared-posix.klib' })
$interopArchive = Join-Path (Get-Location) "ci-output/native-core/$name"
if ($manifests.Count -eq 1) {
    [IO.Compression.ZipFile]::CreateFromDirectory($manifests[0].Directory.Parent.FullName, $interopArchive,
        [IO.Compression.CompressionLevel]::Optimal, $false)
} else {
    $packed = @(Get-ChildItem $interopRoot -File -Filter '*.klib')
    if ($manifests.Count -ne 0 -or $packed.Count -ne 1) { throw 'Missing or ambiguous owned process interop dependency.' }
    Copy-Item -LiteralPath $packed[0].FullName -Destination $interopArchive
}

$summary = [ordered]@{
    target = $Target
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    tests = $tests
    groups = $groupTests
    skipped = 0
    failures = 0
    errors = 0
    interopLibrary = $name
    interopSha256 = (Get-FileHash $interopArchive).Hash.ToLowerInvariant()
    library = $libraries[0].Name
    librarySha256 = (Get-FileHash $libraries[0].FullName).Hash.ToLowerInvariant()
    libraryBytes = $libraries[0].Length
    compilerVersion = $manifest['compiler_version']
    nativeTarget = $konanTarget
    scope = 'shared-native-core-files-process-and-all-regressions-not-cli-or-ui'
}
$summary | ConvertTo-Json -Depth 4 | Set-Content ci-output/native-core-evidence.json -Encoding utf8
Write-Host "NATIVE_CORE_EVIDENCE=SUCCESS target=$Target tests=$tests"
