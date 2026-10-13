param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('linux-x64', 'macos-arm64')]
    [string]$Platform
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$expected = @(
    'FfmpegIntegrationTest', 'FfmpegFrameIntegrationTest',
    'FfmpegRemuxIntegrationTest', 'FfmpegTrimIntegrationTest',
    'FfmpegTranscodeIntegrationTest', 'FfmpegAudioTrimIntegrationTest',
    'FfmpegReplaceIntegrationTest', 'FfmpegCreateTrimIntegrationTest',
    'FfmpegCreateReplacementIntegrationTest'
)
$results = @()
foreach ($name in $expected) {
    $package = if ($name -in @('FfmpegIntegrationTest','FfmpegFrameIntegrationTest','FfmpegReplaceIntegrationTest')) {
        'livephoto.core.media'
    } else { 'livephoto.core.jvm' }
    $file = "core/build/test-results/jvmTest/TEST-$package.$name.xml"
    if (-not (Test-Path $file)) { throw "Missing real FFmpeg test report: $name" }
    [xml]$report = Get-Content -LiteralPath $file -Raw
    $suite = $report.testsuite
    if ([int]$suite.tests -le 0 -or [int]$suite.failures -ne 0 -or
        [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0 -or
        @($suite.SelectNodes('testcase/skipped')).Count -ne 0) {
        throw "Real FFmpeg suite did not fully execute successfully: $name"
    }
    $results += [ordered]@{ suite = $name; tests = [int]$suite.tests; skipped = 0; failures = 0; errors = 0 }
}
$log = Get-Content ci-output/portable.log -Raw
$markers = @{
    'DECODE' = 1; 'REMUX' = 1; 'AAC_REMUX' = 1; 'TWO_AAC_REMUX' = 3
    'AUDIO_TRIM' = 6; 'FRAME' = 1; 'TRANSCODE' = 1; 'AAC_TRANSCODE' = 4
    'REPLACE' = 1; 'TRIM' = 1; 'EXACT_TRIM' = 1; 'EXACT_CREATE_CONVERT' = 1
    'VENDOR_EDITS' = 1; 'CREATE_CONVERT_TRIM' = 1; 'CREATE_CONVERT_REPLACEMENT' = 1
}
foreach ($marker in $markers.Keys) {
    $count = [regex]::Matches($log, "(?m)^PORTABLE_FFMPEG_${marker}=SUCCESS(?:\s|$)").Count
    if ($count -ne $markers[$marker]) { throw "Missing/duplicate portable FFmpeg evidence: $marker ($count)" }
}
if ($log -match 'PORTABLE_FFMPEG_\w+=UNAVAILABLE' -or
    $log -notmatch "(?m)^PORTABLE_SUCCESS=$Platform ") {
    throw 'Portable FFmpeg validation was skipped or did not finish'
}
if (-not (Test-Path ci-output/ffmpeg-version.txt)) { throw 'Missing FFmpeg version evidence' }
$summary = [ordered]@{
    platform = $Platform
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    ffmpeg = (Get-Content ci-output/ffmpeg-version.txt)[0]
    suites = $results
    portableMarkers = $markers
    scope = 'real-ffmpeg-core-and-packaged-cli-not-device-compatibility'
}
$summary | ConvertTo-Json -Depth 8 | Set-Content ci-output/ffmpeg-evidence.json -Encoding utf8
Write-Host "FFMPEG_PLATFORM_EVIDENCE=SUCCESS platform=$Platform suites=$($results.Count)"
