param([Parameter(Mandatory = $true)][ValidateSet('linuxX64', 'macosArm64', 'mingwX64')][string]$Target)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$reports = @(Get-ChildItem "process/build/test-results/${Target}Test" -File -Filter 'TEST-*.xml')
if ($reports.Count -eq 0) { throw 'Missing Native process test reports.' }
$tests = 0
foreach ($file in $reports) {
    [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
    $suite = $report.testsuite
    if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0 -or
        @($suite.SelectNodes('testcase/skipped')).Count -ne 0) { throw 'Native process test failure or skip.' }
    $tests += [int]$suite.tests
}
$expected = 0
Get-ChildItem process/src/commonTest -Recurse -File -Filter '*.kt' | ForEach-Object {
    $expected += [regex]::Matches((Get-Content $_.FullName -Raw), '@Test\b').Count
}
if ($tests -ne $expected -or $expected -le 0) { throw 'Incomplete Native process test execution.' }
$log = Get-Content ci-output/native-core.log -Raw
if ($log -notmatch ":process:${Target}Test(?:\s|$)" -or
    $log -match ":process:${Target}Test (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)") {
    throw 'Native process tests were not freshly executed.'
}
$manifests = @(Get-ChildItem "process/build/classes/kotlin/$Target/main" -Recurse -File -Filter manifest |
    Where-Object { $_.Directory.Name -eq 'default' })
if ($manifests.Count -ne 1) { throw 'Missing Native process library.' }
$archive = Join-Path (Get-Location) 'ci-output/native-core/process.klib'
[IO.Compression.ZipFile]::CreateFromDirectory($manifests[0].Directory.Parent.FullName, $archive,
    [IO.Compression.CompressionLevel]::Optimal, $false)
[ordered]@{
    target = $Target
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    tests = $tests
    failures = 0
    errors = 0
    skipped = 0
    librarySha256 = (Get-FileHash $archive).Hash.ToLowerInvariant()
    scope = 'native-argv-process-pipes-timeout-cancellation-resource-cleanup-not-ffmpeg-backend'
} | ConvertTo-Json | Set-Content ci-output/process-evidence.json -Encoding utf8
Write-Host "NATIVE_PROCESS_EVIDENCE=SUCCESS target=$Target tests=$tests"
