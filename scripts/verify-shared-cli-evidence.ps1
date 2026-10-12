param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('linuxX64', 'macosArm64', 'mingwX64')]
    [string]$Target
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$reports = @(Get-ChildItem "cli-shared/build/test-results/${Target}Test" -File -Filter 'TEST-*.xml')
if ($reports.Count -eq 0) { throw 'Missing shared CLI Native test reports.' }
$tests = 0
foreach ($file in $reports) {
    [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
    $suite = $report.testsuite
    if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or
        [int]$suite.skipped -ne 0 -or @($suite.SelectNodes('testcase/skipped')).Count -ne 0) {
        throw "Shared CLI tests failed or skipped: $($file.Name)"
    }
    $tests += [int]$suite.tests
}
$expected = 0
Get-ChildItem cli-shared/src/commonTest -Recurse -File -Filter '*.kt' | ForEach-Object {
    $expected += [regex]::Matches((Get-Content $_.FullName -Raw), '@Test\b').Count
}
if ($expected -le 0 -or $tests -ne $expected) {
    throw "Incomplete shared CLI tests: $tests executed, $expected declared."
}
$log = Get-Content ci-output/native-core.log -Raw
if ($log -notmatch ":cli-shared:${Target}Test(?:\s|$)" -or
    $log -match ":cli-shared:${Target}Test (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)") {
    throw 'Shared CLI Native tests were not freshly executed.'
}
[ordered]@{
    target = $Target
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    tests = $tests
    failures = 0
    errors = 0
    skipped = 0
    scope = 'shared-cli-engine-json-diagnostics-tests-not-native-cli-executable'
} | ConvertTo-Json | Set-Content ci-output/shared-cli-evidence.json -Encoding utf8
Write-Host "SHARED_CLI_EVIDENCE=SUCCESS target=$Target tests=$tests"
