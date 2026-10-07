param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('windows-x64', 'linux-x64', 'macos-arm64')]
    [string]$Platform,
    [string]$Destination = 'ci-output/portable'
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$repository = (Get-Location).Path
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/jpackage$(if ($IsWindows) { '.exe' })")) {
    throw 'An existing JDK 25 with jpackage is required. This script never installs Java.'
}
$os = if ($IsWindows) { 'windows' } elseif ($IsMacOS) { 'macos' } else { 'linux' }
$architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
if ("$os-$architecture" -ne $Platform) { throw "Wrong host: $os-$architecture; requested $Platform" }
$javaInfo = (& "$env:JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaInfo -notmatch 'java.specification.version = 25') { throw 'Existing Java must be JDK 25.' }
if ($Platform -eq 'macos-arm64' -and $javaInfo -notmatch 'os.arch = aarch64') { throw 'macOS package must use an ARM64 JDK, not Rosetta.' }
if ($Platform -ne 'macos-arm64' -and $javaInfo -notmatch 'os.arch = (amd64|x86_64)') { throw 'Windows/Linux packages must use an x64 JDK.' }
$inputLib = 'cli/build/install/cli/lib'
if (-not (Test-Path "$inputLib/livephoto-cli.jar")) { throw 'Run the Wrapper :cli:installDist task first.' }
$destinationPath = [IO.Path]::GetFullPath($Destination)
$imageParent = Join-Path $destinationPath "image-$Platform"
if (Test-Path $imageParent) { throw 'Package image destination already exists; use a fresh destination.' }
New-Item -ItemType Directory -Path $imageParent -Force | Out-Null
$packageArgs = @('--type', 'app-image', '--name', 'LivePhoto', '--app-version', '0.1.0',
    '--input', $inputLib, '--main-jar', 'livephoto-cli.jar', '--main-class', 'livephoto.cli.MainKt',
    '--add-modules', 'java.base', '--dest', $imageParent)
if ($IsWindows) { $packageArgs += '--win-console' }
& "$env:JAVA_HOME/bin/jpackage" @packageArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
$name = if ($IsMacOS) { 'LivePhoto.app' } else { 'LivePhoto' }
$archive = Join-Path $destinationPath "LivePhoto-$Platform$(if ($IsWindows) { '.zip' } else { '.tar.gz' })"
if ($IsWindows) { Compress-Archive -Path (Join-Path $imageParent $name) -DestinationPath $archive }
else {
    & tar -czf $archive -C $imageParent $name
    if ($LASTEXITCODE -ne 0) { throw 'Archive creation failed' }
}
# Test the archived package after extraction, with a path containing spaces and no reliance
# on the repository working directory. Tar preserves the Unix executable permission bits.
$verify = Join-Path $destinationPath "verify $Platform"
New-Item -ItemType Directory -Path $verify | Out-Null
if ($IsWindows) { Expand-Archive -Path $archive -DestinationPath $verify }
else {
    & tar -xzf $archive -C $verify
    if ($LASTEXITCODE -ne 0) { throw 'Archive extraction failed' }
}
$launcher = Join-Path $verify $(if ($IsWindows) { 'LivePhoto/LivePhoto.exe' } elseif ($IsMacOS) { 'LivePhoto.app/Contents/MacOS/LivePhoto' } else { 'LivePhoto/bin/LivePhoto' })
Push-Location $verify
try {
    & $launcher --version
    if ($LASTEXITCODE -ne 0) { throw 'Portable version smoke test failed' }
    & $launcher --help
    if ($LASTEXITCODE -ne 0) { throw 'Portable help smoke test failed' }
    $capabilityJson = & $launcher capabilities --target google.microvideo.v1
    if ($LASTEXITCODE -ne 0) { throw 'Portable Core capabilities smoke test failed' }
    $createCapability = ($capabilityJson | ConvertFrom-Json).result.operations | Where-Object operation -eq 'Create'
    if ($createCapability.implementation -ne 'Experimental') { throw 'Portable Core capability registry is not accessible.' }
    & $launcher detect --input does-not-exist.jpg
    if ($LASTEXITCODE -ne 3) { throw 'Portable IO/error exit-code smoke test failed' }
    $referenceImage = Join-Path $repository 'reference/video.jpg'
    $referenceVideo = Join-Path $repository 'reference/video.mp4'
    $mediaJson = & $launcher media-capabilities --ffmpeg (Join-Path $verify 'missing-ffmpeg.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Portable media discovery failed.' }
    $media = ($mediaJson | ConvertFrom-Json).result
    if (-not ($media.discoveryIssues | Where-Object { $_.code.value -eq 'FFMPEG_EXPLICIT_PATH_UNAVAILABLE' })) {
        throw 'Portable media discovery did not report the unavailable explicit tool.'
    }
    $probeJson = & $launcher probe --input $referenceVideo --decode-check
    if ($media.ffmpegPath) {
        if ($LASTEXITCODE -ne 0 -or -not ((($probeJson | ConvertFrom-Json).result.issues) | Where-Object { $_.code.value -eq 'MEDIA_DECODE_COMPLETED' })) {
            throw "Portable existing-FFmpeg decode failed: $probeJson"
        }
        Write-Host 'PORTABLE_FFMPEG_DECODE=SUCCESS'
    } else {
        if ($LASTEXITCODE -ne 3 -or ($probeJson | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') {
            throw "Portable missing-backend gate failed: $probeJson"
        }
        Write-Host 'PORTABLE_FFMPEG_DECODE=UNAVAILABLE'
    }
    $createdJson = & $launcher create --image $referenceImage --video $referenceVideo --target google.motionphoto.v2 --output-dir (Join-Path $verify 'roundtrip')
    if ($LASTEXITCODE -ne 0) { throw "Portable reference Create failed: $createdJson" }
    $created = $createdJson | ConvertFrom-Json
    $livePath = $created.result.output.assets[0].path
    & $launcher validate --input $livePath --layers Structure,Protocol
    if ($LASTEXITCODE -ne 0) { throw 'Portable reference validation failed' }
    $extractedJson = & $launcher extract --input $livePath --output-dir (Join-Path $verify 'extracted')
    if ($LASTEXITCODE -ne 0) { throw "Portable reference Extract failed: $extractedJson" }
    $extracted = $extractedJson | ConvertFrom-Json
    if ((Get-FileHash $referenceVideo).Hash -ne (Get-FileHash $extracted.result.output.assets[0].path).Hash) {
        throw 'Portable roundtrip changed the original video bytes.'
    }
} finally { Pop-Location }
$hash = (Get-FileHash -Algorithm SHA256 $archive).Hash.ToLowerInvariant()
"$hash  $([IO.Path]::GetFileName($archive))" | Set-Content "$archive.sha256" -Encoding ascii
Write-Host "PORTABLE_SUCCESS=$Platform archive=$archive SHA256=$hash"
