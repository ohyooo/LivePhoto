param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('linuxX64', 'macosArm64', 'mingwX64')]
    [string]$Target
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$reports = @(Get-ChildItem "file-io/build/test-results/${Target}Test" -File -Filter 'TEST-*.xml')
if ($reports.Count -eq 0) { throw 'Missing file IO Native test reports.' }
$tests = 0
foreach ($file in $reports) {
    [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
    $suite = $report.testsuite
    if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or
        [int]$suite.skipped -ne 0 -or @($suite.SelectNodes('testcase/skipped')).Count -ne 0) {
        throw "File IO tests failed or skipped: $($file.Name)"
    }
    $tests += [int]$suite.tests
}
$expected = 0
Get-ChildItem file-io/src/commonTest -Recurse -File -Filter '*.kt' | ForEach-Object {
    $expected += [regex]::Matches((Get-Content $_.FullName -Raw), '@Test\b').Count
}
if ($expected -le 0 -or $tests -ne $expected) {
    throw "Incomplete file IO tests: $tests executed, $expected declared."
}
$log = Get-Content ci-output/native-core.log -Raw
if ($log -notmatch ":file-io:${Target}Test(?:\s|$)" -or
    $log -match ":file-io:${Target}Test (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)") {
    throw 'File IO Native tests were not freshly executed.'
}
$manifests = @(Get-ChildItem "file-io/build/classes/kotlin/$Target/main" -Recurse -File -Filter manifest |
    Where-Object { $_.Directory.Name -eq 'default' })
if ($manifests.Count -ne 1) { throw 'Expected one compiled file IO main library.' }
$manifest = @{}
Get-Content $manifests[0].FullName | ForEach-Object {
    $parts = $_.Split('=', 2)
    if ($parts.Count -eq 2) { $manifest[$parts[0]] = $parts[1] }
}
$konanTarget = @{ linuxX64 = 'linux_x64'; macosArm64 = 'macos_arm64'; mingwX64 = 'mingw_x64' }[$Target]
if ($manifest['builtins_platform'] -ne 'NATIVE' -or $manifest['native_targets'] -ne $konanTarget) {
    throw 'File IO library is not the requested Native target.'
}
$archive = Join-Path (Get-Location) 'ci-output/native-core/file-io.klib'
[IO.Compression.ZipFile]::CreateFromDirectory($manifests[0].Directory.Parent.FullName, $archive,
    [IO.Compression.CompressionLevel]::Optimal, $false)
$library = Get-Item $archive
if ($library.Length -le 0) { throw 'Empty Native file IO library.' }
[ordered]@{
    target = $Target
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    tests = $tests
    failures = 0
    errors = 0
    skipped = 0
    library = $library.Name
    librarySha256 = (Get-FileHash $archive).Hash.ToLowerInvariant()
    libraryBytes = $library.Length
    compilerVersion = $manifest['compiler_version']
    nativeTarget = $konanTarget
    dependencies = $manifest['depends']
    scope = 'real-native-local-files-snapshots-atomic-asset-sets-and-reference-core-not-native-cli-or-media'
} | ConvertTo-Json | Set-Content ci-output/file-io-evidence.json -Encoding utf8
Write-Host "FILE_IO_EVIDENCE=SUCCESS target=$Target tests=$tests"
