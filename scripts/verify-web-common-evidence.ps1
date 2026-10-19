param([ValidateSet('js', 'wasmJs')][string]$Target = 'js')
$ErrorActionPreference = 'Stop'
$prefix = if ($Target -eq 'wasmJs') { 'web-wasm-common' } else { 'web-common' }
Set-Location (Split-Path $PSScriptRoot -Parent)
$expected = @{}
Get-ChildItem shared/src/commonTest/kotlin -Recurse -File -Filter '*.kt' | ForEach-Object {
    $source = Get-Content $_.FullName -Raw
    $count = [regex]::Matches($source, '@Test\b').Count
    if ($count -eq 0) { return }
    $package = [regex]::Match($source, '(?m)^package\s+([\w.]+)').Groups[1].Value
    $class = [regex]::Match($source, '(?m)^(?:internal |public )?class\s+(\w+)').Groups[1].Value
    $name = "$package.$class"
    if (-not $package -or -not $class -or $expected.ContainsKey($name)) { throw 'Ambiguous common test class.' }
    $expected[$name] = $count
}
$declared = ($expected.Values | Measure-Object -Sum).Sum
if ($declared -lt 804) { throw 'Existing common protocol tests were lost.' }
$reports = @(Get-ChildItem "shared/build/test-results/${Target}BrowserTest" -File -Filter 'TEST-*.xml')
if ($reports.Count -eq 0) { throw 'No executed Kotlin/JS browser test reports.' }
$destination = "ci-output/$prefix-results"
New-Item -ItemType Directory -Path $destination | Out-Null
$seen = @{}
$tests = 0
$files = @()
foreach ($file in $reports) {
    [xml]$report = Get-Content $file.FullName -Raw
    $suite = $report.testsuite
    if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0 -or
        @($suite.SelectNodes('testcase/skipped')).Count -ne 0 -or
        [int]$suite.tests -ne @($suite.SelectNodes('testcase')).Count) { throw "Failed/skipped common tests: $($file.Name)" }
    $matches = @($expected.Keys | Where-Object { $suite.name -eq $_ -or $suite.name.EndsWith(".$_") })
    if ($matches.Count -ne 1) { throw "Unexpected common suite: $($suite.name)" }
    $name = $matches[0]
    if ($seen.ContainsKey($name) -or [int]$suite.tests -ne $expected[$name]) { throw "Incomplete/duplicate common suite: $name" }
    $seen[$name] = [int]$suite.tests
    $tests += [int]$suite.tests
    Copy-Item $file.FullName $destination
    $files += [ordered]@{ path = "$prefix-results/$($file.Name)"; bytes = $file.Length; sha256 = (Get-FileHash $file.FullName).Hash.ToLowerInvariant() }
}
if ($tests -ne $declared -or $seen.Count -ne $expected.Count) { throw "Incomplete common execution: $tests / $declared" }
$log = Get-Content "ci-output/$prefix.log" -Raw
if ($log -notmatch ":shared:${Target}BrowserTest(?:\s|$)" -or $log -match ":shared:${Target}BrowserTest (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)") {
    throw 'Kotlin/JS common tests must actually execute, not restore cached results.'
}
[ordered]@{
    commit = $env:GITHUB_SHA; run = $env:GITHUB_RUN_ID
    scope = 'kotlin-commonTest-pure-protocol-real-chrome-no-host-backend'
    kotlinRuntime = $Target
    tests = $tests; expectedTests = $declared; failures = 0; skipped = 0
    suites = @($seen.Keys | Sort-Object | ForEach-Object { [ordered]@{ name = $_; tests = $seen[$_] } })
    reports = $files
} | ConvertTo-Json -Depth 6 | Set-Content "ci-output/$prefix-evidence.json" -Encoding utf8
Write-Host "WEB_COMMON_EVIDENCE=SUCCESS $tests Kotlin commonTest cases; real Chrome; no failures or skips"
