param(
    [Parameter(Mandatory = $true)][ValidateSet('linux-x64', 'macos-arm64', 'windows-x64')][string]$Platform,
    [Parameter(Mandatory = $true)][ValidateSet('linuxX64', 'macosArm64', 'mingwX64')][string]$Target
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$repository = (Get-Location).Path
$tests = 0
$reports = @(Get-ChildItem "cli-native/build/test-results/${Target}Test" -File -Filter 'TEST-*.xml')
if ($reports.Count -eq 0) { throw 'Missing Native CLI test reports.' }
foreach ($report in $reports) {
    [xml]$xml = Get-Content $report.FullName -Raw
    if ([int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0) {
        throw 'Native CLI tests failed or skipped.'
    }
    $tests += [int]$xml.testsuite.tests
}
$expected = 0
foreach ($source in @('commonTest', 'nativeTest')) {
    Get-ChildItem "cli-native/src/$source" -Recurse -File -Filter '*.kt' | ForEach-Object {
        $expected += [regex]::Matches((Get-Content $_.FullName -Raw), '@Test\b').Count
    }
}
if ($tests -ne $expected -or $tests -eq 0) { throw 'Incomplete Native CLI test execution.' }
$log = Get-Content ci-output/native-core.log -Raw
if ($log -notmatch ":cli-native:${Target}Test(?:\s|$)" -or
    $log -match ":cli-native:${Target}Test (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)") {
    throw 'Native CLI tests must execute freshly.'
}
$binary = @(Get-ChildItem "cli-native/build/bin/$Target/releaseExecutable" -File |
    Where-Object { $_.Name -match '^LivePhoto\.(exe|kexe)$' })
if ($binary.Count -ne 1) { throw 'Expected one release Native executable.' }
$header = [IO.File]::ReadAllBytes($binary[0].FullName)
$magic = [Convert]::ToHexString($header[0..3])
if (($Platform -eq 'windows-x64' -and $magic -notmatch '^4D5A') -or
    ($Platform -eq 'linux-x64' -and $magic -ne '7F454C46') -or
    ($Platform -eq 'macos-arm64' -and $magic -ne 'CFFAEDFE')) { throw 'Wrong executable format.' }
$destination = Join-Path $repository 'ci-output/native-cli'
if (Test-Path $destination) { throw 'Use a fresh Native CLI output directory.' }
New-Item -ItemType Directory $destination | Out-Null
$archive = Join-Path $destination "LivePhoto-native-$Platform$(if ($IsWindows) { '.zip' } else { '.tar.gz' })"
if ($IsWindows) { Compress-Archive $binary[0].FullName $archive }
else {
    & tar -czf $archive -C $binary[0].Directory.FullName $binary[0].Name
    if ($LASTEXITCODE -ne 0) { throw 'Native archive creation failed.' }
}
$verify = Join-Path $destination 'verify native 路径'
New-Item -ItemType Directory $verify | Out-Null
if ($IsWindows) { Expand-Archive $archive $verify }
else {
    & tar -xzf $archive -C $verify
    if ($LASTEXITCODE -ne 0) { throw 'Native archive extraction failed.' }
}
$launcher = Join-Path $verify $binary[0].Name
$workspace = Join-Path $verify 'private workspace 输入'
New-Item -ItemType Directory $workspace | Out-Null
if (-not $IsWindows) {
    & chmod 700 $workspace
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict smoke workspace permissions.' }
}
$script:checks = 0
function Invoke-Native([string[]]$arguments, [int]$expectedExit = 0, [bool]$withWorkspace = $true, [bool]$json = $true) {
    $start = [Diagnostics.ProcessStartInfo]::new($launcher)
    $start.UseShellExecute = $false
    $start.WorkingDirectory = $verify
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.Encoding]::UTF8
    $start.StandardErrorEncoding = [Text.Encoding]::UTF8
    foreach ($argument in $arguments) { $start.ArgumentList.Add($argument) }
    $start.Environment['JAVA_HOME'] = Join-Path $verify 'no-java-runtime'
    $start.Environment['LIVEPHOTO_LOG_LEVEL'] = 'trace'
    $null = $start.Environment.Remove('LIVEPHOTO_WORKSPACE')
    if ($withWorkspace) { $start.Environment['LIVEPHOTO_WORKSPACE'] = $workspace }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(120000)) { $process.Kill($true); throw 'Native CLI smoke timed out.' }
        $output = $stdout.GetAwaiter().GetResult()
        $diagnostics = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne $expectedExit) { throw "Native CLI exit mismatch: $output" }
        if (@(Get-ChildItem $workspace -Force).Count -ne 0) { throw 'Native CLI leaked an input snapshot.' }
        if ($diagnostics.Contains($repository) -or $diagnostics.Contains($workspace)) { throw 'Diagnostics leaked private paths.' }
        Add-Content (Join-Path $repository 'ci-output/native-cli-smoke.log') $diagnostics
        $script:checks++
        if ($json) { return ($output | ConvertFrom-Json) }
        return $output.Trim()
    } finally { $process.Dispose() }
}
$image = Join-Path $repository 'reference/video.jpg'
$video = Join-Path $repository 'reference/video.mp4'
$imageHash = (Get-FileHash $image).Hash
$videoHash = (Get-FileHash $video).Hash
if ((Invoke-Native @('--version') 0 $false $false) -ne 'LivePhoto 0.1.0') { throw 'Native version failed.' }
if ((Invoke-Native @('--help') 0 $false $false) -notmatch 'LivePhoto Core CLI') { throw 'Native help failed.' }
$missing = Invoke-Native @('detect', '--input', $image) 3 $false
if ($missing.error.code.value -ne 'INVALID_ARGUMENT') { throw 'Missing private workspace was not rejected.' }
$budget = Invoke-Native @('create', '--image', $image, '--video', $video, '--target', 'oplus.olive', '--output-dir', (Join-Path $verify 'over-budget'), '--max-bytes', '100') 3
if ($budget.error.code.value -ne 'RESOURCE_LIMIT_EXCEEDED') { throw 'Input budget was not enforced.' }
$created = Invoke-Native @('create', '--image', $image, '--video', $video, '--target', 'oplus.olive', '--profile', 'jpeg-no-tail', '--output-dir', (Join-Path $verify 'created live'))
if ($created.result.output.receipt.state -ne 'Committed') { throw 'Native output was not committed.' }
$carrier = $created.result.output.assets[0].path
$inspected = (Invoke-Native @('inspect', '--input', $carrier)).result
if ($inspected.detection.primaryProtocol.protocol.value -ne 'oplus.olive') { throw 'Native protocol mismatch.' }
$validation = Invoke-Native @('validate', '--input', $carrier, '--layers', 'Structure,Protocol')
if ($validation.result.verdict -eq 'Invalid') { throw 'Native carrier validation failed.' }
$primary = $inspected.detection.primaryProtocol
$match = @($inspected.detection.matches | Where-Object {
    $_.target.protocol.value -eq $primary.protocol.value -and $_.target.profile.value -eq $primary.profile.value
})
if ($match.Count -ne 1) { throw 'Expected a unique primary detection match.' }
$ids = @($match[0].resourceIds | ForEach-Object { $_.value })
$resource = @($inspected.layout.resources | Where-Object { $_.kind -eq 'Video' -and $_.id.value -in $ids })
if ($resource.Count -ne 1) { throw 'Expected one primary video resource.' }
$extracted = Invoke-Native @('extract', '--input', $carrier, '--resources', $resource[0].id.value, '--output-dir', (Join-Path $verify 'extracted video'))
if ((Get-FileHash $extracted.result.output.assets[0].path).Hash -ne $videoHash) { throw 'Native extraction was not byte exact.' }
$capabilities = Invoke-Native @('capabilities', '--target', 'oplus.olive', '--profile', 'jpeg-no-tail')
if (-not $capabilities.result) { throw 'Native protocol capabilities missing.' }
$media = Invoke-Native @('media-capabilities', '--ffmpeg', (Join-Path $verify 'not-invoked-ffmpeg'))
if ($media.result.capabilities.backendIds.Count -ne 0 -or -not ($media.result.discoveryIssues | Where-Object { $_.code.value -eq 'CAPABILITY_UNSUPPORTED' })) {
    throw 'Unimplemented Native backend must not claim support.'
}
$decode = Invoke-Native @('probe', '--input', $video, '--decode-check') 3
if ($decode.error.code.value -ne 'CAPABILITY_UNSUPPORTED') { throw 'Native decode without backend must fail.' }
if ((Get-FileHash $image).Hash -ne $imageHash -or (Get-FileHash $video).Hash -ne $videoHash) { throw 'Native CLI changed an original.' }
[ordered]@{
    commit = $env:GITHUB_SHA; run = $env:GITHUB_RUN_ID; target = $Target; platform = $Platform
    tests = $tests; skipped = 0; failures = 0; errors = 0; smokeChecks = $script:checks
    binary = $binary[0].Name; binaryBytes = $binary[0].Length; binarySha256 = (Get-FileHash $launcher).Hash.ToLowerInvariant()
    archiveSha256 = (Get-FileHash $archive).Hash.ToLowerInvariant()
    scope = 'native-protocol-cli-no-jvm-no-ffmpeg-no-device-compatibility-claim'
} | ConvertTo-Json | Set-Content ci-output/native-cli-evidence.json -Encoding utf8
Write-Host "NATIVE_CLI_EVIDENCE=SUCCESS target=$Target tests=$tests smoke=$script:checks scope=protocol-only"
