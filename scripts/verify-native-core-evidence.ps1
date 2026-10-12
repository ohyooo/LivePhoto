param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('linuxX64', 'macosArm64', 'mingwX64')]
    [string]$Target
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
Get-ChildItem core/build -Recurse -Filter '*.klib' | ForEach-Object {
    Write-Host "NATIVE_LIBRARY_OUTPUT path=$($_.FullName) directory=$($_.PSIsContainer) bytes=$($_.Length)"
}
$libraries = @(Get-ChildItem core/build/libs -Recurse -File -Filter '*.klib')
if ($libraries.Count -ne 1 -or $libraries[0].Length -le 0) {
    throw "Expected exactly one nonempty compiled Native Core KLIB; found $($libraries.Count)."
}
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
    scope = 'kotlin-native-core-library-and-shared-tests-not-native-cli-or-ffi'
}
$summary | ConvertTo-Json -Depth 4 | Set-Content ci-output/native-core-evidence.json -Encoding utf8
Write-Host "NATIVE_CORE_EVIDENCE=SUCCESS target=$Target tests=$tests"
