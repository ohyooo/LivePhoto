param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('linuxX64', 'macosArm64', 'mingwX64')]
    [string]$Target
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
# Kotlin's compile output is an unpacked KLIB, whose directory need not have
# a .klib suffix. Pack the actual main compilation, never the test binary.
$manifests = @(Get-ChildItem "core/build/classes/kotlin/$Target/main" -Recurse -File -Filter manifest |
    Where-Object { $_.Directory.Name -eq 'default' })
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
$archive = Join-Path $packedDirectory 'core.klib'
[IO.Compression.ZipFile]::CreateFromDirectory($libraryRoot, $archive, [IO.Compression.CompressionLevel]::Optimal, $false)
$libraries = @(Get-Item $archive)
if ($libraries[0].Length -le 0) { throw 'Empty packed Native library.' }
$reports = @(Get-ChildItem "core/build/test-results/${Target}Test" -File -Filter 'TEST-*.xml')
if ($reports.Count -eq 0) { throw 'Missing executed Native test reports.' }
$tests = 0
foreach ($file in $reports) {
    [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
    $suite = $report.testsuite
    if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or
        [int]$suite.skipped -ne 0 -or @($suite.SelectNodes('testcase/skipped')).Count -ne 0) {
        throw "Native shared tests failed or skipped: $($file.Name)"
    }
    $tests += [int]$suite.tests
}
# All existing shared tests must execute, not a tiny Native-only smoke subset.
$expected = 0
Get-ChildItem core/src/commonTest -Recurse -File -Filter '*.kt' | ForEach-Object {
    $expected += [regex]::Matches((Get-Content $_.FullName -Raw), '@Test\b').Count
}
if ($expected -le 0 -or $tests -ne $expected) {
    throw "Incomplete Native test execution: $tests tests, $expected shared @Test declarations."
}
$log = Get-Content ci-output/native-core.log -Raw
if ($log -notmatch ":core:${Target}Test(?:\s|$)" -or
    $log -match ":core:${Target}Test (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)") {
    throw 'Native tests were not freshly executed.'
}
$summary = [ordered]@{
    target = $Target
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    tests = $tests
    skipped = 0
    failures = 0
    errors = 0
    library = $libraries[0].Name
    librarySha256 = (Get-FileHash $libraries[0].FullName).Hash.ToLowerInvariant()
    libraryBytes = $libraries[0].Length
    compilerVersion = $manifest['compiler_version']
    nativeTarget = $konanTarget
    scope = 'kotlin-native-core-library-and-shared-tests-not-native-cli-or-ffi'
}
$summary | ConvertTo-Json -Depth 4 | Set-Content ci-output/native-core-evidence.json -Encoding utf8
Write-Host "NATIVE_CORE_EVIDENCE=SUCCESS target=$Target tests=$tests"
