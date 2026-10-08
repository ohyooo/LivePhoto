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
    '--add-modules', 'java.base,java.desktop', '--java-options', '-Djava.awt.headless=true', '--dest', $imageParent)
if ($IsWindows) {
    $packageArgs += @('--win-console', '--add-launcher', "WindowsMediaHelper=$(Join-Path $PSScriptRoot 'windows-media-worker.properties')")
}
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
    if ($IsWindows) {
        # Independent launcher uses the bundled runtime. Native access is restricted to this worker,
        # not granted to the primary CLI; bootstrap does not advertise a media decode capability.
        $mediaWorker = Join-Path $verify 'LivePhoto/WindowsMediaHelper.exe'
        if (-not (Test-Path $mediaWorker)) { throw 'Portable isolated Windows worker launcher is missing.' }
        $workerJson = & $mediaWorker --preflight
        $workerExit = $LASTEXITCODE
        if (($workerExit -eq 0 -and "$workerJson" -notmatch 'WINDOWS_MEDIA_API_PREFLIGHT=SUCCESS scope=runtime-bootstrap-not-media-decode') -or
            ($workerExit -eq 3 -and "$workerJson" -notmatch 'WINDOWS_MEDIA_API_PREFLIGHT=UNAVAILABLE') -or $workerExit -notin @(0, 3)) {
            throw "Portable Windows worker bootstrap failed: $workerJson"
        }
        if ($env:LIVEPHOTO_REQUIRE_WINDOWS_MEDIA -eq 'true' -and $workerExit -ne 0) { throw 'Required Windows system API bootstrap is unavailable in the portable worker.' }
        Write-Host "PORTABLE_WINDOWS_API_BOOTSTRAP=$(if ($workerExit -eq 0) { 'SUCCESS' } else { 'UNAVAILABLE' }) scope=runtime-not-decode"
        $decoderJson = & $mediaWorker --decoder-preflight
        $decoderExit = $LASTEXITCODE
        if (($decoderExit -eq 0 -and "$decoderJson" -notmatch 'WINDOWS_MEDIA_API_DECODER=SUCCESS scope=registered-software-avc-to-nv12') -or
            ($decoderExit -eq 3 -and "$decoderJson" -notmatch 'UNAVAILABLE') -or $decoderExit -notin @(0, 3)) {
            throw "Portable OS decoder enumeration failed: $decoderJson"
        }
        $windowsDecoderAvailable = $decoderExit -eq 0
        if ($env:LIVEPHOTO_REQUIRE_WINDOWS_MEDIA -eq 'true' -and -not $windowsDecoderAvailable) { throw 'Required OS AVC decoder is unavailable.' }
    }
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
    # Core Clean needs no FFmpeg/encoder/decoder. Run this on every packaged platform,
    # including CI runners without external media tools, using independently hashed fixtures.
    $neutralFixtures = Join-Path $repository 'core/build/portable-heic-fixtures'
    $neutralManifest = @{}
    foreach ($line in Get-Content (Join-Path $neutralFixtures 'manifest.txt')) {
        $parts = $line.Split('=', 2); if ($parts.Count -eq 2) { $neutralManifest[$parts[0]] = $parts[1] }
    }
    if ($neutralManifest['scope'] -ne 'synthetic-protocol-framing-not-decoder-or-device-proof' -or -not $neutralManifest['runId']) {
        throw 'Portable neutral fixture provenance is missing.'
    }
    $neutralSearchPath = $env:Path
    try {
        $env:Path = ''
        foreach ($name in @('motion')) {
            $fixture = Join-Path $neutralFixtures "$name.mp4"
            if ((Get-FileHash $fixture).Hash.ToLowerInvariant() -ne $neutralManifest["$name.mp4"]) { throw 'Neutral fixture hash differs.' }
            $outside = Join-Path $verify "neutral synthetic $name.mp4"
            [IO.File]::Copy($fixture, $outside, $false)
            $firstJson = & $launcher split --input $outside --strict --output-dir (Join-Path $verify "neutral tool-free $name")
            if ($LASTEXITCODE -ne 0) { throw "Portable no-FFmpeg movie Clean failed: $firstJson" }
            $first = ($firstJson | ConvertFrom-Json).result
            if ($first.output.assets.Count -ne 1 -or $first.output.assets[0].role -ne 'MotionVideo' -or
                $first.output.assets[0].videoContainer -ne 'Mp4' -or @($first.preservation.changes).Count -ne 0 -or
                ($first.execution | Where-Object { $_.transcoded -or $_.remuxed }) -or
                ($first.preservation.records | Where-Object { $_.outcome -notin @('Verified', 'NotApplicable') })) {
                throw 'Portable no-FFmpeg movie Clean violated its finite scope.'
            }
            $secondJson = & $launcher split --input $first.output.assets[0].path --strict --output-dir (Join-Path $verify "neutral tool-free repeat $name")
            if ($LASTEXITCODE -ne 0) { throw "Portable no-FFmpeg repeated Clean failed: $secondJson" }
            $second = ($secondJson | ConvertFrom-Json).result
            foreach ($path in @($fixture, $outside, $first.output.assets[0].path, $second.output.assets[0].path)) {
                if ((Get-FileHash $path).Hash.ToLowerInvariant() -ne $neutralManifest["$name.mp4"]) { throw 'No-FFmpeg Clean was not byte-exact or mutated input.' }
            }
        }
    } finally { $env:Path = $neutralSearchPath }
    Write-Host 'PORTABLE_NEUTRAL_MOVIE_NO_FFMPEG=SUCCESS scope=synthetic-avc-aac-framing-file-exact-idempotence-not-decode-or-device'
    if ($IsWindows -and $windowsDecoderAvailable) {
        $windowsFixtures = Join-Path $repository 'core/build/portable-windows-fixtures'
        $fixtureManifest = @{}
        foreach ($line in Get-Content (Join-Path $windowsFixtures 'manifest.txt')) {
            $parts = $line.Split('=', 2); if ($parts.Count -eq 2) { $fixtureManifest[$parts[0]] = $parts[1] }
        }
        if ($fixtureManifest['scope'] -ne 'nas-encoded-synthetic-avc-not-device-proof' -or -not $fixtureManifest['runId']) {
            throw 'Portable encoded OS fixtures have no classified test proof.'
        }
        $savedSearchPath = $env:Path
        try {
            $env:Path = ''
            $systemJson = & $launcher media-capabilities --ffmpeg (Join-Path $verify 'absent fixture-encoder.exe')
            if ($LASTEXITCODE -ne 0) { throw 'Portable system-only discovery failed.' }
            $system = ($systemJson | ConvertFrom-Json).result
            if ($system.ffmpegPath -or $system.capabilities.backendIds.Count -ne 1 -or $system.capabilities.backendIds[0] -ne 'windows-media-foundation') {
                throw 'Portable system route requires the bundled helper, not PATH or external Java.'
            }
            foreach ($name in @('b0', 'b2', 'vfr', 'audio')) {
                $inputFixture = Join-Path $windowsFixtures "$name.mp4"
                if ((Get-FileHash $inputFixture).Hash.ToLowerInvariant() -ne $fixtureManifest["$name.mp4"]) { throw 'OS fixture digest differs.' }
                $outsideFixture = Join-Path $verify "encoded OS $name.mp4"
                [IO.File]::Copy($inputFixture, $outsideFixture, $false)
                $systemProbe = & $launcher probe --input $outsideFixture --decode-check
                if ($name -eq 'audio') {
                    if ($LASTEXITCODE -ne 3 -or ($systemProbe | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') {
                        throw 'System backend must reject audio rather than claim complete video-only decode.'
                    }
                } elseif ($LASTEXITCODE -ne 0 -or ($systemProbe | ConvertFrom-Json).result.coverage -ne 'Partial' -or
                    -not (($systemProbe | ConvertFrom-Json).result.issues | Where-Object { $_.code.value -eq 'MEDIA_DECODE_COMPLETED' })) {
                    throw "Portable encoded fixture OS decode failed: $systemProbe"
                }
                if ((Get-FileHash $outsideFixture).Hash.ToLowerInvariant() -ne $fixtureManifest["$name.mp4"]) { throw 'OS decoder mutated source.' }
            }
        } finally { $env:Path = $savedSearchPath }
        Write-Host 'PORTABLE_WINDOWS_API_ENCODED_FIXTURES=SUCCESS scope=avc-bframes-vfr-audio-rejection-no-ffmpeg-no-system-java-not-device'
    }
    $probeJson = & $launcher probe --input $referenceVideo --decode-check
    if ($media.ffmpegPath) {
        if ($LASTEXITCODE -ne 0 -or -not ((($probeJson | ConvertFrom-Json).result.issues) | Where-Object { $_.code.value -eq 'MEDIA_DECODE_COMPLETED' })) {
            throw "Portable existing-FFmpeg decode failed: $probeJson"
        }
        Write-Host 'PORTABLE_FFMPEG_DECODE=SUCCESS'
        if ($IsWindows -and $windowsDecoderAvailable) {
            # Explicit fixture encoding only: the OS worker itself never launches FFmpeg.
            # Microsoft H.264 decoding requires at least 48x48, unlike the smaller Core fixtures.
            $ptsBytes = [Collections.Generic.List[byte]]::new()
            foreach ($pts in @([long]0, [long]400000, [long]800000, [long]1200000)) {
                $bytes = [BitConverter]::GetBytes($pts)
                if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($bytes) }
                $ptsBytes.AddRange($bytes)
            }
            $ptsHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($ptsBytes.ToArray())).ToLowerInvariant()
            foreach ($bFrames in @(0, 2)) {
                $osFixture = Join-Path $verify "OS AVC B $bFrames fixture.mp4"
                & $media.ffmpegPath -nostdin -n -hide_banner -loglevel error -xerror -f lavfi -i 'testsrc2=size=64x64:rate=25' -frames:v 4 -c:v libx264 -preset medium -bf $bFrames -g 4 -pix_fmt yuv420p $osFixture
                if ($LASTEXITCODE -ne 0) { throw 'Portable OS decode fixture generation failed.' }
                $osHash = (Get-FileHash $osFixture).Hash
                $start = [Diagnostics.ProcessStartInfo]::new($mediaWorker)
                $start.UseShellExecute = $false
                $start.RedirectStandardOutput = $true
                $start.RedirectStandardError = $true
                foreach ($argument in @('--decode-video', $osFixture, '4', '65536', '128000000')) { $start.ArgumentList.Add($argument) }
                $process = [Diagnostics.Process]::Start($start)
                try {
                    $stdout = $process.StandardOutput.ReadToEndAsync()
                    $stderr = $process.StandardError.ReadToEndAsync()
                    if (-not $process.WaitForExit(30000)) { $process.Kill($true); throw 'Portable OS decoder timed out.' }
                    $trace = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
                    if ($process.ExitCode -ne 0 -or $trace.Length -gt 65536 -or
                        $trace -notmatch "WINDOWS_MEDIA_API_DECODE=SUCCESS scope=selected-avc-video frames=4 width=64 height=64 ptsSha256=$ptsHash" -or
                        $osHash -ne (Get-FileHash $osFixture).Hash) { throw "Portable OS decoder failed independent frame/timeline/source checks: $trace" }
                } finally { $process.Dispose() }
                $savedSearchPath = $env:Path
                try {
                    $env:Path = ''
                    $systemJson = & $launcher media-capabilities --ffmpeg (Join-Path $verify 'missing OS-fallback ffmpeg.exe')
                    if ($LASTEXITCODE -ne 0) { throw 'Portable OS fallback discovery failed.' }
                    $system = ($systemJson | ConvertFrom-Json).result
                    if ($system.ffmpegPath -or $system.capabilities.backendIds.Count -ne 1 -or
                        $system.capabilities.backendIds[0] -ne 'windows-media-foundation' -or
                        ($system.discoveryIssues | Where-Object { $_.code.value -eq 'MEDIA_BACKEND_UNAVAILABLE' })) {
                        throw 'Portable no-PATH route did not discover the bounded bundled OS worker.'
                    }
                    $systemProbe = & $launcher probe --input $osFixture --decode-check
                    if ($LASTEXITCODE -ne 0 -or ($systemProbe | ConvertFrom-Json).result.coverage -ne 'Partial' -or
                        -not (($systemProbe | ConvertFrom-Json).result.issues | Where-Object { $_.code.value -eq 'MEDIA_DECODE_COMPLETED' })) {
                        throw "Portable Core system-only decode failed: $systemProbe"
                    }
                } finally { $env:Path = $savedSearchPath }
                if ($osHash -ne (Get-FileHash $osFixture).Hash) { throw 'Portable Core OS decoder mutated input.' }
            }
            Write-Host 'PORTABLE_WINDOWS_API_DECODE=SUCCESS scope=selected-avc-video-not-audio-or-device'
            Write-Host 'PORTABLE_WINDOWS_API_CORE_FALLBACK=SUCCESS scope=finite-probe-only-no-ffmpeg-no-system-java'
        }
        # Explicitly generated fixture: its encoding is not part of the remux operation.
        $remuxFixture = Join-Path $verify 'remux fixture.mp4'
        & $media.ffmpegPath -nostdin -n -hide_banner -loglevel error -xerror -f lavfi -i 'color=c=black:s=16x16:r=25' -frames:v 4 -c:v libx264 -preset ultrafast -bf 0 -g 2 -pix_fmt yuv420p -metadata:s:v 'encoder=' -fflags +bitexact -flags:v +bitexact -write_btrt 0 $remuxFixture
        if ($LASTEXITCODE -ne 0) { throw 'Portable remux fixture generation failed.' }
        $remuxJson = & $launcher remux --input $remuxFixture --container Mov --strict --output-dir (Join-Path $verify 'remux result')
        if ($LASTEXITCODE -ne 0) { throw "Portable real remux failed: $remuxJson" }
        $remux = ($remuxJson | ConvertFrom-Json).result
        if ($remux.output.assets[0].videoContainer -ne 'Mov' -or ($remux.execution | Where-Object transcoded -eq $true) -or
            -not ($remux.preservation.records | Where-Object { $_.guarantee -eq 'BitstreamPreserving' -and $_.outcome -eq 'Verified' })) {
            throw 'Portable remux did not return verified no-encoding evidence.'
        }
        Write-Host 'PORTABLE_FFMPEG_REMUX=SUCCESS'
        foreach ($neutralPath in @($remuxFixture, $remux.output.assets[0].path)) {
            $neutralIndex = if ($neutralPath -eq $remuxFixture) { 'mp4' } else { 'mov' }
            $neutralHash = (Get-FileHash $neutralPath -Algorithm SHA256).Hash
            $cleanJson = & $launcher split --input $neutralPath --strict --output-dir (Join-Path $verify "neutral clean $neutralIndex")
            if ($LASTEXITCODE -ne 0) { throw "Portable neutral movie Clean failed: $cleanJson" }
            $clean = ($cleanJson | ConvertFrom-Json).result
            if ($clean.output.assets.Count -ne 1 -or $clean.output.assets[0].role -ne 'MotionVideo' -or
                (Get-FileHash $clean.output.assets[0].path -Algorithm SHA256).Hash -ne $neutralHash -or
                @($clean.preservation.changes).Count -ne 0 -or ($clean.execution | Where-Object { $_.transcoded -or $_.remuxed }) -or
                ($clean.preservation.records | Where-Object { $_.outcome -notin @('Verified', 'NotApplicable') })) {
                throw 'Portable neutral movie Clean violated file-exact/no-edit preservation'
            }
            $repeatJson = & $launcher split --input $clean.output.assets[0].path --strict --output-dir (Join-Path $verify "neutral repeat $neutralIndex")
            if ($LASTEXITCODE -ne 0) { throw "Portable repeated movie Clean failed: $repeatJson" }
            $repeat = ($repeatJson | ConvertFrom-Json).result
            if ((Get-FileHash $repeat.output.assets[0].path -Algorithm SHA256).Hash -ne $neutralHash -or
                (Get-FileHash $neutralPath -Algorithm SHA256).Hash -ne $neutralHash) { throw 'Movie Clean was not byte-exact/idempotent or changed input' }
            & $media.ffmpegPath -nostdin -hide_banner -loglevel error -xerror -i $repeat.output.assets[0].path -map 0:v -f null -
            if ($LASTEXITCODE -ne 0) { throw 'Cleaned neutral movie failed complete AV decode' }
        }
        Write-Host 'PORTABLE_NEUTRAL_MOVIE_CLEAN=SUCCESS scope=bounded-mp4-mov-byte-exact-idempotence-not-device'
        # Explicit synthetic AAC encode + fixture mux, separate from the forbidden-encoding Core remux.
        $aacElementary = Join-Path $verify 'encoded AAC fixture.aac'
        $aacFixture = Join-Path $verify 'audio first MP4 fixture.mp4'
        & $media.ffmpegPath -nostdin -n -hide_banner -loglevel error -xerror -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=0.16' -c:a aac -b:a 96k -ac 2 -flags:a +bitexact -f adts $aacElementary
        if ($LASTEXITCODE -ne 0) { throw 'Portable AAC fixture encode failed.' }
        & $media.ffmpegPath -nostdin -n -hide_banner -loglevel error -xerror -i $remuxFixture -i $aacElementary -map 1:a:0 -map 0:v:0 -streamid 0:4 -streamid 1:17 -c copy -bsf:a aac_adtstoasc -map_metadata -1 -metadata:s:v 'encoder=' -fflags +bitexact -write_btrt 0 -use_stream_ids_as_track_ids 1 $aacFixture
        if ($LASTEXITCODE -ne 0) { throw 'Portable AAC fixture mux failed.' }
        $aacHash = (Get-FileHash $aacFixture).Hash
        $aacJson = & $launcher remux --input $aacFixture --container Mp4 --strict --output-dir (Join-Path $verify 'AAC remux result')
        if ($LASTEXITCODE -ne 0) { throw "Portable AAC remux failed: $aacJson" }
        $aacResult = ($aacJson | ConvertFrom-Json).result
        if (($aacResult.execution | Where-Object transcoded -eq $true) -or -not ($aacResult.execution | Where-Object remuxed -eq $true) -or
            ($aacResult.preservation.records | Where-Object { $_.guarantee -in @('BitstreamPreserving', 'MetadataPreserving') -and $_.outcome -ne 'Verified' }) -or
            $aacHash -ne (Get-FileHash $aacFixture).Hash) { throw 'Portable AAC remux did not preserve both tracks/configuration/metadata/source.' }
        $aacDecode = & $launcher probe --input $aacResult.output.assets[0].path --decode-check
        if ($LASTEXITCODE -ne 0 -or -not (($aacDecode | ConvertFrom-Json).result.issues | Where-Object { $_.code.value -eq 'MEDIA_DECODE_COMPLETED' })) { throw 'Portable AAC remux output did not completely decode.' }
        $aacMov = & $launcher remux --input $aacFixture --container Mov --output-dir (Join-Path $verify 'unsupported AAC MOV')
        if ($LASTEXITCODE -ne 3 -or ($aacMov | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') { throw 'Portable unimplemented AAC MOV remux gate failed.' }
        Write-Host 'PORTABLE_FFMPEG_AAC_REMUX=SUCCESS'
        $frameFixture = Join-Path $verify 'frame fixture.mp4'
        & $media.ffmpegPath -nostdin -n -hide_banner -loglevel error -xerror -f lavfi -i 'testsrc2=size=32x32:rate=25' -frames:v 4 -vf 'setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709' -c:v libx264 -preset medium -bf 2 -g 4 -pix_fmt yuv420p -color_range tv -colorspace bt709 -color_primaries bt709 -color_trc bt709 -metadata:s:v 'encoder=' -fflags +bitexact -flags:v +bitexact -write_btrt 0 $frameFixture
        if ($LASTEXITCODE -ne 0) { throw 'Portable frame fixture generation failed.' }
        $frameJson = & $launcher extract-frame --input $frameFixture --frame-index 1 --format Jpeg --output-dir (Join-Path $verify 'frame result')
        if ($LASTEXITCODE -ne 0) { throw "Portable real frame extraction failed: $frameJson" }
        $frame = ($frameJson | ConvertFrom-Json).result
        if ($frame.actualFrameIndex -ne '1' -or $frame.operation.output.assets[0].imageFormat -ne 'Jpeg' -or
            -not ($frame.operation.execution | Where-Object stage -eq 'EncodeImage') -or ($frame.operation.execution | Where-Object transcoded -eq $true)) {
            throw 'Portable frame result does not describe the requested derived image.'
        }
        Write-Host 'PORTABLE_FFMPEG_FRAME=SUCCESS'
        foreach ($appleVideo in @($remuxFixture, $aacFixture)) {
            $appleIndex = if ($appleVideo -eq $remuxFixture) { 'video' } else { 'audio-first' }
            $appleInputHash = (Get-FileHash $appleVideo).Hash
            $appleCarrierJson = & $launcher create --image $frame.operation.output.assets[0].path --video $appleVideo --target google.motionphoto.v2 --frame-index 1 --strict --output-dir (Join-Path $verify "Apple source $appleIndex")
            if ($LASTEXITCODE -ne 0) { throw "Portable Apple source assembly failed: $appleCarrierJson" }
            $appleCarrier = ($appleCarrierJson | ConvertFrom-Json).result.output.assets[0].path
            $appleCarrierHash = (Get-FileHash $appleCarrier).Hash
            $appleJson = & $launcher convert --input $appleCarrier --target apple.livephoto --profile jpeg-mp4 --strict --output-dir (Join-Path $verify "Apple pair $appleIndex")
            if ($LASTEXITCODE -ne 0) { throw "Portable Apple ConvertTo failed: $appleJson" }
            $apple = ($appleJson | ConvertFrom-Json).result
            if ($apple.output.assets.Count -ne 2 -or $apple.output.assets[0].role -ne 'PrimaryImage' -or $apple.output.assets[1].role -ne 'MotionVideo' -or
                $apple.validation.verdict -ne 'Valid' -or $apple.validation.coverage -ne 'Complete' -or ($apple.execution | Where-Object transcoded -eq $true) -or
                ($apple.preservation.records | Where-Object { $_.outcome -notin @('Verified','NotApplicable') })) { throw 'Portable Apple output lacks a verified complete atomic pair.' }
            $appleImage = $apple.output.assets[0].path
            $appleMovie = $apple.output.assets[1].path
            $appleValidate = & $launcher validate --input $appleImage --pair-video $appleMovie --layers Structure,Protocol
            if ($LASTEXITCODE -ne 0 -or ($appleValidate | ConvertFrom-Json).result.verdict -ne 'Valid') { throw 'Portable Apple pair failed independent validation.' }
            & $media.ffmpegPath -nostdin -hide_banner -loglevel error -xerror -err_detect explode -f mov -i $appleMovie -map 0:v -map '0:a?' -sn -dn -f null -
            if ($LASTEXITCODE -ne 0) { throw 'Portable Apple movie did not completely decode all audio/video.' }
            $appleRoundtripJson = & $launcher convert --input $appleImage --pair-video $appleMovie --target google.motionphoto.v2 --output-dir (Join-Path $verify "Apple roundtrip $appleIndex")
            if ($LASTEXITCODE -ne 0) { throw "Portable Apple ConvertFrom failed: $appleRoundtripJson" }
            $appleRoundtrip = ($appleRoundtripJson | ConvertFrom-Json).result.output.assets[0].path
            $appleKeyJson = & $launcher get-key --input $appleRoundtrip
            if ($LASTEXITCODE -ne 0 -or ($appleKeyJson | ConvertFrom-Json).result.position.value -ne '40000' -or ($appleKeyJson | ConvertFrom-Json).result.position.timescale -ne 1000000) { throw 'Portable Apple roundtrip lost the inherited exact key.' }
            $appleExtractJson = & $launcher extract --input $appleRoundtrip --output-dir (Join-Path $verify "Apple roundtrip video $appleIndex")
            if ($LASTEXITCODE -ne 0) { throw 'Portable Apple roundtrip raw video extraction failed.' }
            $appleRoundtripVideo = ($appleExtractJson | ConvertFrom-Json).result.output.assets[0].path
            & $media.ffmpegPath -nostdin -hide_banner -loglevel error -xerror -err_detect explode -f mov -i $appleRoundtripVideo -map 0:v -map '0:a?' -sn -dn -f null -
            if ($LASTEXITCODE -ne 0 -or (Get-FileHash $appleVideo).Hash -ne $appleInputHash -or (Get-FileHash $appleCarrier).Hash -ne $appleCarrierHash) { throw 'Portable Apple roundtrip failed decode or changed its borrowed sources.' }
            # Independent ordinary-media Create entrance; no Google carrier or CLI-side protocol assembly.
            $plainImage = $frame.operation.output.assets[0].path
            $plainImageHash = (Get-FileHash $plainImage).Hash
            $directJson = & $launcher create --image $plainImage --video $appleVideo --target apple.livephoto --profile jpeg-mp4 --frame-index 0 --strict --output-dir (Join-Path $verify "Apple direct create $appleIndex")
            if ($LASTEXITCODE -ne 0) { throw "Portable Apple direct Create failed: $directJson" }
            $direct = ($directJson | ConvertFrom-Json).result
            if ($direct.output.assets.Count -ne 2 -or $direct.validation.verdict -ne 'Valid' -or $direct.validation.coverage -ne 'Complete' -or
                ($direct.execution | Where-Object { $_.transcoded -or $_.stage -in @('DecodeFrame','EncodeImage') }) -or
                ($direct.preservation.records | Where-Object { $_.outcome -notin @('Verified','NotApplicable') })) { throw 'Portable Apple Create lacks verified no-encoding atomic pair evidence.' }
            $directImage = $direct.output.assets[0].path
            $directMovie = $direct.output.assets[1].path
            $directImageHash = (Get-FileHash $directImage).Hash
            $directMovieHash = (Get-FileHash $directMovie).Hash
            $directInspectJson = & $launcher inspect --input $directImage --pair-video $directMovie
            if ($LASTEXITCODE -ne 0) { throw 'Portable Apple Create independent inspect failed.' }
            $directPairing = ($directInspectJson | ConvertFrom-Json).result.pairing | ConvertTo-Json -Depth 20 -Compress
            $keyImage = $directImage; $keyMovie = $directMovie
            foreach ($keyIndex in @(1, 0)) {
                $setJson = & $launcher set-key --input $keyImage --pair-video $keyMovie --frame-index $keyIndex --strict --output-dir (Join-Path $verify "Apple key $appleIndex $keyIndex")
                if ($LASTEXITCODE -ne 0) { throw "Portable Apple SetKey failed: $setJson" }
                $set = ($setJson | ConvertFrom-Json).result
                if ($set.output.assets.Count -ne 2 -or ($set.execution | Where-Object { $_.transcoded -or $_.remuxed -or $_.stage -in @('DecodeFrame','EncodeImage') }) -or
                    ($set.preservation.records | Where-Object { $_.outcome -notin @('Verified','NotApplicable') })) { throw 'Portable Apple SetKey changed media or lacks preservation proof.' }
                $keyImage = $set.output.assets[0].path; $keyMovie = $set.output.assets[1].path
                if ((Get-FileHash $keyImage).Hash -ne $directImageHash) { throw 'Portable Apple SetKey changed the complete primary image.' }
                $keyJson = & $launcher get-key --input $keyImage --pair-video $keyMovie
                if ($LASTEXITCODE -ne 0) { throw 'Portable Apple SetKey independent key read failed.' }
                $keyPosition = ($keyJson | ConvertFrom-Json).result.position
                if (-not $keyPosition -or ([decimal]$keyPosition.value * 1000000 / $keyPosition.timescale) -ne ($keyIndex * 40000)) { throw 'Portable Apple SetKey lost the requested exact presentation time.' }
                $keyInspectJson = & $launcher inspect --input $keyImage --pair-video $keyMovie
                if ($LASTEXITCODE -ne 0 -or (($keyInspectJson | ConvertFrom-Json).result.pairing | ConvertTo-Json -Depth 20 -Compress) -ne $directPairing) { throw 'Portable Apple SetKey changed pair CID or matching facts.' }
                $keyValidateJson = & $launcher validate --input $keyImage --pair-video $keyMovie --layers Structure,Protocol
                if ($LASTEXITCODE -ne 0 -or ($keyValidateJson | ConvertFrom-Json).result.verdict -ne 'Valid' -or ($keyValidateJson | ConvertFrom-Json).result.coverage -ne 'Complete') { throw 'Portable Apple SetKey pair failed complete independent validation.' }
                & $media.ffmpegPath -nostdin -hide_banner -loglevel error -xerror -err_detect explode -f mov -i $keyMovie -map 0:v -map '0:a?' -sn -dn -f null -
                if ($LASTEXITCODE -ne 0) { throw 'Portable Apple SetKey movie failed complete AV decoding.' }
            }
            if ((Get-FileHash $keyMovie).Hash -ne $directMovieHash -or (Get-FileHash $directMovie).Hash -ne $directMovieHash -or
                (Get-FileHash $directImage).Hash -ne $directImageHash -or (Get-FileHash $plainImage).Hash -ne $plainImageHash -or
                (Get-FileHash $appleVideo).Hash -ne $appleInputHash) { throw 'Portable Apple zero/nonzero/zero key roundtrip changed unrequested bytes or borrowed inputs.' }
        }
        Write-Host 'PORTABLE_APPLE_PAIR_ROUNDTRIP=SUCCESS'
        Write-Host 'PORTABLE_APPLE_CREATE_SETKEY=SUCCESS scope=bounded-jpeg-mp4-not-device-compatibility'
        $movSource = $remux.output.assets[0].path
        $movSourceHash = (Get-FileHash $movSource).Hash
        $movCreateJson = & $launcher create --image $frame.operation.output.assets[0].path --video $movSource --target apple.livephoto --profile jpeg-mov --frame-index 0 --strict --output-dir (Join-Path $verify 'Apple direct MOV create')
        if ($LASTEXITCODE -ne 0) { throw "Portable Apple MOV Create failed: $movCreateJson" }
        $movCreate = ($movCreateJson | ConvertFrom-Json).result
        if ($movCreate.output.assets.Count -ne 2 -or $movCreate.output.assets[1].videoContainer -ne 'Mov' -or $movCreate.output.assets[1].mime -ne 'video/quicktime' -or
            ($movCreate.execution | Where-Object { $_.transcoded -or $_.remuxed })) { throw 'Portable Apple MOV Create changed the input container or did not publish a complete pair.' }
        $movImage = $movCreate.output.assets[0].path; $movMovie = $movCreate.output.assets[1].path
        $movImageHash = (Get-FileHash $movImage).Hash; $movMovieHash = (Get-FileHash $movMovie).Hash
        $movInspectJson = & $launcher inspect --input $movImage --pair-video $movMovie
        if ($LASTEXITCODE -ne 0) { throw 'Portable Apple MOV initial pair inspect failed.' }
        $movPairing = ($movInspectJson | ConvertFrom-Json).result.pairing | ConvertTo-Json -Depth 20 -Compress
        foreach ($keyIndex in @(1, 0)) {
            $movSetJson = & $launcher set-key --input $movImage --pair-video $movMovie --frame-index $keyIndex --strict --output-dir (Join-Path $verify "Apple MOV key $keyIndex")
            if ($LASTEXITCODE -ne 0) { throw "Portable Apple MOV SetKey failed: $movSetJson" }
            $movSet = ($movSetJson | ConvertFrom-Json).result
            if ($movSet.output.assets.Count -ne 2 -or $movSet.output.assets[1].videoContainer -ne 'Mov' -or
                ($movSet.execution | Where-Object { $_.transcoded -or $_.remuxed }) -or
                ($movSet.preservation.records | Where-Object { $_.outcome -notin @('Verified','NotApplicable') })) { throw 'Portable Apple MOV SetKey lacks exact owned-patch preservation.' }
            $movImage = $movSet.output.assets[0].path; $movMovie = $movSet.output.assets[1].path
            $movKeyJson = & $launcher get-key --input $movImage --pair-video $movMovie
            if ($LASTEXITCODE -ne 0) { throw 'Portable Apple MOV independent key read failed.' }
            $movPosition = ($movKeyJson | ConvertFrom-Json).result.position
            if (-not $movPosition -or ([decimal]$movPosition.value * 1000000 / $movPosition.timescale) -ne ($keyIndex * 40000)) { throw 'Portable Apple MOV key PTS is not exact.' }
            $movInspectJson = & $launcher inspect --input $movImage --pair-video $movMovie
            if ($LASTEXITCODE -ne 0 -or (($movInspectJson | ConvertFrom-Json).result.pairing | ConvertTo-Json -Depth 20 -Compress) -ne $movPairing -or (Get-FileHash $movImage).Hash -ne $movImageHash) { throw 'Portable Apple MOV SetKey changed the original primary or CID.' }
            $movValidateJson = & $launcher validate --input $movImage --pair-video $movMovie --layers Structure,Protocol
            if ($LASTEXITCODE -ne 0 -or ($movValidateJson | ConvertFrom-Json).result.verdict -ne 'Valid' -or ($movValidateJson | ConvertFrom-Json).result.coverage -ne 'Complete') { throw 'Portable Apple MOV pair validation failed.' }
            & $media.ffmpegPath -nostdin -hide_banner -loglevel error -xerror -err_detect explode -f mov -i $movMovie -map 0:v -map '0:a?' -sn -dn -f null -
            if ($LASTEXITCODE -ne 0) { throw 'Portable Apple MOV SetKey movie failed complete decoding.' }
        }
        if ((Get-FileHash $movMovie).Hash -ne $movMovieHash -or (Get-FileHash $movSource).Hash -ne $movSourceHash) { throw 'Portable Apple MOV exact key roundtrip changed unrequested or source bytes.' }
        Write-Host 'PORTABLE_APPLE_MOV_CREATE_SETKEY=SUCCESS scope=bounded-classified-mov-not-device-compatibility'
        $movCarrierJson = & $launcher create --image $frame.operation.output.assets[0].path --video $movSource --target google.motionphoto.v2 --frame-index 1 --strict --output-dir (Join-Path $verify 'Apple MOV live source')
        if ($LASTEXITCODE -ne 0) { throw "Portable MOV live source Create failed: $movCarrierJson" }
        $movCarrier = ($movCarrierJson | ConvertFrom-Json).result.output.assets[0].path
        $movCarrierHash = (Get-FileHash $movCarrier).Hash
        $movConvertJson = & $launcher convert --input $movCarrier --target apple.livephoto --profile jpeg-mov --strict --output-dir (Join-Path $verify 'Apple MOV ConvertTo')
        if ($LASTEXITCODE -ne 0) { throw "Portable Apple MOV ConvertTo failed: $movConvertJson" }
        $movConverted = ($movConvertJson | ConvertFrom-Json).result
        if ($movConverted.output.assets.Count -ne 2 -or $movConverted.output.assets[1].videoContainer -ne 'Mov' -or
            $movConverted.output.assets[1].mime -ne 'video/quicktime' -or ($movConverted.execution | Where-Object { $_.transcoded -or $_.remuxed }) -or
            ($movConverted.preservation.records | Where-Object { $_.outcome -notin @('Verified','NotApplicable') })) {
            throw 'Portable MOV conversion did not preserve classified carriers/sample semantics.'
        }
        $convertedImage = $movConverted.output.assets[0].path
        $convertedMovie = $movConverted.output.assets[1].path
        $convertedValidation = & $launcher validate --input $convertedImage --pair-video $convertedMovie --layers Structure,Protocol
        if ($LASTEXITCODE -ne 0 -or ($convertedValidation | ConvertFrom-Json).result.verdict -ne 'Valid' -or
            ($convertedValidation | ConvertFrom-Json).result.coverage -ne 'Complete') { throw 'Portable MOV conversion pair validation failed.' }
        $convertedKey = & $launcher get-key --input $convertedImage --pair-video $convertedMovie
        if ($LASTEXITCODE -ne 0) { throw 'Portable MOV converted key inspection failed.' }
        $position = ($convertedKey | ConvertFrom-Json).result.position
        if (-not $position -or ([decimal]$position.value * 1000000 / $position.timescale) -ne 40000) { throw 'MOV conversion lost inherited presentation key.' }
        & $media.ffmpegPath -nostdin -hide_banner -loglevel error -xerror -err_detect explode -f mov -i $convertedMovie -map 0:v -map '0:a?' -sn -dn -f null -
        if ($LASTEXITCODE -ne 0 -or (Get-FileHash $movCarrier).Hash -ne $movCarrierHash -or (Get-FileHash $movSource).Hash -ne $movSourceHash) {
            throw 'MOV conversion decode/source immutability checks failed.'
        }
        Write-Host 'PORTABLE_APPLE_MOV_CONVERT=SUCCESS scope=bounded-classified-mov-not-device-compatibility'
        $transcodeFixture = Join-Path $verify 'transcode fixture.mp4'
        & $media.ffmpegPath -nostdin -n -hide_banner -loglevel error -xerror -f lavfi -i 'testsrc2=size=32x32:rate=25' -frames:v 8 -vf "setpts='if(lt(N,4),N,4+(N-4)*2)/(25*TB)',setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709" -fps_mode passthrough -c:v libx264 -preset ultrafast -crf 30 -bf 0 -g 3 -pix_fmt yuv420p -use_editlist 0 -metadata:s:v 'encoder=' -fflags +bitexact -flags:v +bitexact -write_btrt 0 $transcodeFixture
        if ($LASTEXITCODE -ne 0) { throw 'Portable transcode fixture generation failed.' }
        $transcodeSourceHash = (Get-FileHash $transcodeFixture).Hash
        $forbiddenJson = & $launcher transcode --input $transcodeFixture --codec Avc --container Mp4 --output-dir (Join-Path $verify 'forbidden transcode')
        if ($LASTEXITCODE -ne 3 -or ($forbiddenJson | ConvertFrom-Json).error.code.value -ne 'TRANSCODE_NOT_AUTHORIZED') { throw 'Portable default policy authorized encoding.' }
        $transcodeJson = & $launcher transcode --input $transcodeFixture --codec Avc --container Mp4 --allow-transcode --output-dir (Join-Path $verify 'transcode result')
        if ($LASTEXITCODE -ne 0) { throw "Portable real authorized transcode failed: $transcodeJson" }
        $transcode = ($transcodeJson | ConvertFrom-Json).result
        if (-not ($transcode.execution | Where-Object { $_.stage -eq 'Transcode' -and $_.transcoded -and $_.hardwareUsed -eq $false -and -not $_.remuxed }) -or
            -not ($transcode.preservation.records | Where-Object { $_.guarantee -eq 'BitstreamPreserving' -and $_.outcome -eq 'Changed' }) -or
            $transcodeSourceHash -ne (Get-FileHash $transcodeFixture).Hash) { throw 'Portable transcode misreported encoding or changed its source.' }
        $transcodeDecode = & $launcher probe --input $transcode.output.assets[0].path --decode-check
        if ($LASTEXITCODE -ne 0) { throw "Portable encoded video could not be completely decoded: $transcodeDecode" }
        Write-Host 'PORTABLE_FFMPEG_TRANSCODE=SUCCESS'
        # The freshly derived JPEG has no source EXIF/ICC/auxiliary dependencies.
        # Use Core Create, never a script-side protocol writer, to build the fixture.
        $replaceCarrierJson = & $launcher create --image $frame.operation.output.assets[0].path --video $frameFixture --target google.motionphoto.v2 --output-dir (Join-Path $verify 'replace carrier')
        if ($LASTEXITCODE -ne 0) { throw "Portable replace carrier creation failed: $replaceCarrierJson" }
        $replaceCarrier = ($replaceCarrierJson | ConvertFrom-Json).result.output.assets[0].path
        $replaceJson = & $launcher replace-cover --input $replaceCarrier --frame-index 2 --format Jpeg --update-key --output-dir (Join-Path $verify 'replace result')
        if ($LASTEXITCODE -ne 0) { throw "Portable real image replacement failed: $replaceJson" }
        $replace = ($replaceJson | ConvertFrom-Json).result
        if (-not ($replace.preservation.records | Where-Object { $_.guarantee -eq 'ImageDataPreserving' -and $_.outcome -eq 'Changed' }) -or
            ($replace.execution | Where-Object transcoded -eq $true) -or
            ([decimal]$replace.keyPhoto.position.value * 1000000 / $replace.keyPhoto.position.timescale) -ne 80000) {
            throw 'Portable replacement did not disclose changed coding and explicit source-domain key synchronization.'
        }
        $replaceExtractJson = & $launcher extract --input $replace.output.assets[0].path --output-dir (Join-Path $verify 'replace extracted')
        if ($LASTEXITCODE -ne 0 -or (Get-FileHash $frameFixture).Hash -ne (Get-FileHash ($replaceExtractJson | ConvertFrom-Json).result.output.assets[0].path).Hash) {
            throw 'Portable replacement changed the original motion video bytes.'
        }
        Write-Host 'PORTABLE_FFMPEG_REPLACE=SUCCESS'
        $trimJson = & $launcher trim --input $remuxFixture --start-us 0 --end-us 80000 --mode LosslessOnly --strict --output-dir (Join-Path $verify 'trim result')
        if ($LASTEXITCODE -ne 0) { throw "Portable real lossless trim failed: $trimJson" }
        $trim = ($trimJson | ConvertFrom-Json).result
        if ($trim.wasTranscoded -or $trim.retainedHiddenContent -or -not $trim.wasBitstreamPreserved -or $trim.actualStart.value -ne '0' -or
            ([decimal]$trim.actualEnd.value * 1000000 / $trim.actualEnd.timescale) -ne 80000 -or ($trim.operation.execution | Where-Object transcoded -eq $true)) {
            throw 'Portable trim did not disclose the proved nonencoding boundaries.'
        }
        $trimDecode = & $launcher probe --input $trim.operation.output.assets[0].path --decode-check
        if ($LASTEXITCODE -ne 0) { throw "Portable trimmed video could not be decoded: $trimDecode" }
        Write-Host 'PORTABLE_FFMPEG_TRIM=SUCCESS'
        $exactForbidden = & $launcher trim --input $transcodeFixture --start-us 40000 --end-us 400000 --mode Exact --output-dir (Join-Path $verify 'exact forbidden')
        if ($LASTEXITCODE -ne 3 -or ($exactForbidden | ConvertFrom-Json).error.code.value -ne 'EXACT_TRIM_UNAVAILABLE') { throw 'Portable Exact silently encoded without authorization.' }
        $exactJson = & $launcher trim --input $transcodeFixture --start-us 40000 --end-us 400000 --mode Exact --allow-transcode --output-dir (Join-Path $verify 'exact trim result')
        if ($LASTEXITCODE -ne 0) { throw "Portable authorized Exact VFR trim failed: $exactJson" }
        $exact = ($exactJson | ConvertFrom-Json).result
        if (-not $exact.wasTranscoded -or $exact.wasBitstreamPreserved -or $exact.wasRemuxed -or $exact.retainedHiddenContent -or
            ([decimal]$exact.actualStart.value * 1000000 / $exact.actualStart.timescale) -ne 40000 -or
            ([decimal]$exact.actualEnd.value * 1000000 / $exact.actualEnd.timescale) -ne 400000 -or
            -not ($exact.operation.preservation.records | Where-Object { $_.guarantee -eq 'BitstreamPreserving' -and $_.outcome -eq 'Changed' })) { throw 'Portable Exact did not disclose changed coding and precise boundaries.' }
        $exactDecode = & $launcher probe --input $exact.operation.output.assets[0].path --decode-check
        if ($LASTEXITCODE -ne 0) { throw "Portable Exact output failed full decoding: $exactDecode" }
        Write-Host 'PORTABLE_FFMPEG_EXACT_TRIM=SUCCESS'
        $exactCreateJson = & $launcher create --image $frame.operation.output.assets[0].path --video $transcodeFixture --target google.microvideo.v1 --start-us 40000 --end-us 400000 --mode Exact --allow-transcode --frame-index 2 --replacement-frame-index 7 --output-dir (Join-Path $verify 'exact replacement create')
        if ($LASTEXITCODE -ne 0) { throw "Portable Exact replacement Create failed: $exactCreateJson" }
        $exactCreate = ($exactCreateJson | ConvertFrom-Json).result
        if (([decimal]$exactCreate.keyPhoto.position.value * 1000000 / $exactCreate.keyPhoto.position.timescale) -ne 40000 -or
            -not ($exactCreate.preservation.records | Where-Object { $_.guarantee -eq 'BitstreamPreserving' -and $_.outcome -eq 'Changed' }) -or
            -not ($exactCreate.preservation.records | Where-Object { $_.guarantee -eq 'ImageDataPreserving' -and $_.outcome -eq 'Changed' })) { throw 'Portable Exact replacement Create falsely claimed original media preservation or conflated key/replacement frame.' }
        $exactCarrierJson = & $launcher create --image $frame.operation.output.assets[0].path --video $transcodeFixture --target google.microvideo.v1 --frame-index 2 --output-dir (Join-Path $verify 'exact original carrier')
        if ($LASTEXITCODE -ne 0) { throw "Portable Exact source carrier failed: $exactCarrierJson" }
        $exactConvertJson = & $launcher convert --input ($exactCarrierJson | ConvertFrom-Json).result.output.assets[0].path --target google.motionphoto.v2 --start-us 40000 --end-us 400000 --mode Exact --allow-transcode --output-dir (Join-Path $verify 'exact convert result')
        if ($LASTEXITCODE -ne 0) { throw "Portable Exact Convert failed: $exactConvertJson" }
        $exactConvert = ($exactConvertJson | ConvertFrom-Json).result
        if (([decimal]$exactConvert.keyPhoto.position.value * 1000000 / $exactConvert.keyPhoto.position.timescale) -ne 40000 -or
            -not ($exactConvert.preservation.records | Where-Object { $_.guarantee -eq 'BitstreamPreserving' -and $_.outcome -eq 'Changed' })) { throw 'Portable Exact Convert lost source-domain key or coding change.' }
        $exactConvertedVideo = & $launcher extract --input $exactConvert.output.assets[0].path --output-dir (Join-Path $verify 'exact converted extracted')
        if ($LASTEXITCODE -ne 0) { throw 'Portable Exact Convert extraction failed.' }
        $exactCompositeDecode = & $launcher probe --input ($exactConvertedVideo | ConvertFrom-Json).result.output.assets[0].path --decode-check
        if ($LASTEXITCODE -ne 0) { throw "Portable Exact Convert derived video failed decoding: $exactCompositeDecode" }
        Write-Host 'PORTABLE_FFMPEG_EXACT_CREATE_CONVERT=SUCCESS'
        foreach ($vendorTarget in @('oplus.olive', 'samsung.motionphoto', 'vivo.motionphoto')) {
            $vendorEditCreateJson = & $launcher create --image $frame.operation.output.assets[0].path --video $transcodeFixture --target $vendorTarget --start-us 40000 --end-us 400000 --mode Exact --allow-transcode --frame-index 2 --replacement-frame-index 7 --output-dir (Join-Path $verify "vendor exact create $vendorTarget")
            if ($LASTEXITCODE -ne 0) { throw "Portable vendor Exact Create failed: $vendorEditCreateJson" }
            $vendorEditConvertJson = & $launcher convert --input ($exactCarrierJson | ConvertFrom-Json).result.output.assets[0].path --target $vendorTarget --start-us 40000 --end-us 400000 --mode Exact --allow-transcode --output-dir (Join-Path $verify "vendor exact convert $vendorTarget")
            if ($LASTEXITCODE -ne 0) { throw "Portable vendor Exact Convert failed: $vendorEditConvertJson" }
            foreach ($kind in @('create', 'convert')) {
                $vendorEditJson = if ($kind -eq 'create') { $vendorEditCreateJson } else { $vendorEditConvertJson }
                $vendorEdited = ($vendorEditJson | ConvertFrom-Json).result
                if (([decimal]$vendorEdited.keyPhoto.position.value * 1000000 / $vendorEdited.keyPhoto.position.timescale) -ne 40000 -or
                    -not ($vendorEdited.preservation.records | Where-Object { $_.guarantee -eq 'BitstreamPreserving' -and $_.outcome -eq 'Changed' })) { throw 'Vendor edit lost source-domain key or falsely claimed original bitstream.' }
                $vendorEditVideo = & $launcher extract --input $vendorEdited.output.assets[0].path --output-dir (Join-Path $verify "vendor exact extracted $vendorTarget $kind")
                if ($LASTEXITCODE -ne 0) { throw 'Vendor edited carrier extraction failed.' }
                $vendorEditDecode = & $launcher probe --input ($vendorEditVideo | ConvertFrom-Json).result.output.assets[0].path --decode-check
                if ($LASTEXITCODE -ne 0) { throw "Vendor edited video failed full decoding: $vendorEditDecode" }
            }
        }
        Write-Host 'PORTABLE_FFMPEG_VENDOR_EDITS=SUCCESS'
        $trimCreateJson = & $launcher create --image $frame.operation.output.assets[0].path --video $remuxFixture --target google.microvideo.v1 --start-us 80000 --end-us 160000 --mode LosslessOnly --frame-index 3 --output-dir (Join-Path $verify 'trim create result')
        if ($LASTEXITCODE -ne 0) { throw "Portable real trim Create failed: $trimCreateJson" }
        $trimCreate = ($trimCreateJson | ConvertFrom-Json).result
        if (([decimal]$trimCreate.keyPhoto.position.value * 1000000 / $trimCreate.keyPhoto.position.timescale) -ne 40000 -or
            -not ($trimCreate.execution | Where-Object stage -eq 'Trim') -or ($trimCreate.execution | Where-Object transcoded -eq $true)) { throw 'Portable Create did not map the source key through actual trim.' }
        $fullCarrierJson = & $launcher create --image $frame.operation.output.assets[0].path --video $remuxFixture --target google.microvideo.v1 --frame-index 3 --output-dir (Join-Path $verify 'trim full carrier')
        if ($LASTEXITCODE -ne 0) { throw "Portable full source carrier failed: $fullCarrierJson" }
        $fullCarrier = ($fullCarrierJson | ConvertFrom-Json).result.output.assets[0].path
        $trimConvertJson = & $launcher convert --input $fullCarrier --target google.motionphoto.v2 --start-us 80000 --end-us 160000 --mode LosslessOnly --output-dir (Join-Path $verify 'trim convert result')
        if ($LASTEXITCODE -ne 0) { throw "Portable real trim Convert failed: $trimConvertJson" }
        $trimConvert = ($trimConvertJson | ConvertFrom-Json).result
        if (([decimal]$trimConvert.keyPhoto.position.value * 1000000 / $trimConvert.keyPhoto.position.timescale) -ne 40000 -or
            -not ($trimConvert.preservation.changes | Where-Object selector -eq 'videoTrim')) { throw 'Portable Convert did not preserve and rebase the inherited key.' }
        $createdVideoJson = & $launcher extract --input $trimCreate.output.assets[0].path --output-dir (Join-Path $verify 'trim create extracted')
        if ($LASTEXITCODE -ne 0) { throw 'Portable trimmed Create extraction failed.' }
        $convertedVideoJson = & $launcher extract --input $trimConvert.output.assets[0].path --output-dir (Join-Path $verify 'trim convert extracted')
        if ($LASTEXITCODE -ne 0 -or (Get-FileHash ($createdVideoJson | ConvertFrom-Json).result.output.assets[0].path).Hash -ne (Get-FileHash ($convertedVideoJson | ConvertFrom-Json).result.output.assets[0].path).Hash) {
            throw 'Portable Create/Convert did not embed the same verified derived video.'
        }
        Write-Host 'PORTABLE_FFMPEG_CREATE_CONVERT_TRIM=SUCCESS'
        $replacementCreateJson = & $launcher create --image $frame.operation.output.assets[0].path --video $transcodeFixture --target google.motionphoto.v2 --replacement-frame-index 5 --frame-index 0 --start-us 0 --end-us 120000 --mode LosslessOnly --output-dir (Join-Path $verify 'replacement trim create')
        if ($LASTEXITCODE -ne 0) { throw "Portable combined frame replacement and trim Create failed: $replacementCreateJson" }
        $replacementCreate = ($replacementCreateJson | ConvertFrom-Json).result
        if (([decimal]$replacementCreate.keyPhoto.position.value) -ne 0 -or
            -not ($replacementCreate.preservation.records | Where-Object { $_.guarantee -eq 'ImageDataPreserving' -and $_.outcome -eq 'Changed' }) -or
            -not ($replacementCreate.execution | Where-Object stage -eq 'Trim') -or ($replacementCreate.execution | Where-Object transcoded -eq $true)) { throw 'Portable replacement/trim conflated source frame with key or video encoding.' }
        $replacementConvertJson = & $launcher convert --input $replaceCarrier --target google.microvideo.v1 --replacement-frame-index 1 --output-dir (Join-Path $verify 'replacement convert')
        if ($LASTEXITCODE -ne 0) { throw "Portable replacement Convert failed: $replacementConvertJson" }
        $replacementConvert = ($replacementConvertJson | ConvertFrom-Json).result
        if (([decimal]$replacementConvert.keyPhoto.position.value * 1000000 / $replacementConvert.keyPhoto.position.timescale) -ne 80000 -or
            -not ($replacementConvert.execution | Where-Object stage -eq 'EncodeImage')) { throw 'Portable replacement Convert lost the inherited key.' }
        Write-Host 'PORTABLE_FFMPEG_CREATE_CONVERT_REPLACEMENT=SUCCESS'
    } else {
        if ($LASTEXITCODE -ne 3 -or ($probeJson | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') {
            throw "Portable missing-backend gate failed: $probeJson"
        }
        Write-Host 'PORTABLE_FFMPEG_DECODE=UNAVAILABLE'
        $remuxJson = & $launcher remux --input $referenceVideo --container Mov --output-dir (Join-Path $verify 'disabled remux')
        if ($LASTEXITCODE -ne 3 -or ($remuxJson | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') {
            throw 'Portable missing-backend remux gate failed.'
        }
        Write-Host 'PORTABLE_FFMPEG_REMUX=UNAVAILABLE'
        $frameJson = & $launcher extract-frame --input $referenceVideo --frame-index 0 --format Jpeg --output-dir (Join-Path $verify 'disabled frame')
        if ($LASTEXITCODE -ne 3 -or ($frameJson | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') { throw 'Portable missing-backend frame gate failed.' }
        Write-Host 'PORTABLE_FFMPEG_FRAME=UNAVAILABLE'
        $replaceJson = & $launcher replace-cover --input $referenceImage --frame-index 0 --format Jpeg --output-dir (Join-Path $verify 'disabled replace')
        if ($LASTEXITCODE -ne 3 -or ($replaceJson | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') { throw 'Portable missing-backend replace gate failed.' }
        Write-Host 'PORTABLE_FFMPEG_REPLACE=UNAVAILABLE'
        $trimJson = & $launcher trim --input $referenceVideo --start-us 0 --end-us 80000 --output-dir (Join-Path $verify 'disabled trim')
        if ($LASTEXITCODE -ne 3 -or ($trimJson | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') { throw 'Portable missing-backend trim gate failed.' }
        Write-Host 'PORTABLE_FFMPEG_TRIM=UNAVAILABLE'
        $transcodeJson = & $launcher transcode --input $referenceVideo --codec Avc --container Mp4 --allow-transcode --output-dir (Join-Path $verify 'disabled transcode')
        if ($LASTEXITCODE -ne 3 -or ($transcodeJson | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') { throw 'Portable missing-backend transcode gate failed.' }
        Write-Host 'PORTABLE_FFMPEG_TRANSCODE=UNAVAILABLE'
        $trimCreateJson = & $launcher create --image $referenceImage --video $referenceVideo --target google.microvideo.v1 --start-us 0 --end-us 80000 --output-dir (Join-Path $verify 'disabled trim create')
        if ($LASTEXITCODE -ne 3 -or ($trimCreateJson | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') { throw 'Portable missing-backend trimmed Create gate failed.' }
        Write-Host 'PORTABLE_FFMPEG_CREATE_TRIM=UNAVAILABLE'
        $replacementCreateJson = & $launcher create --image $referenceImage --video $referenceVideo --target google.motionphoto.v2 --replacement-frame-index 0 --output-dir (Join-Path $verify 'disabled replacement create')
        if ($LASTEXITCODE -ne 3 -or ($replacementCreateJson | ConvertFrom-Json).error.code.value -ne 'CAPABILITY_UNSUPPORTED') { throw 'Portable missing-backend replacement Create gate failed.' }
        Write-Host 'PORTABLE_FFMPEG_CREATE_REPLACEMENT=UNAVAILABLE'
    }
    $createdJson = & $launcher create --image $referenceImage --video $referenceVideo --target google.motionphoto.v2 --output-dir (Join-Path $verify 'roundtrip')
    if ($LASTEXITCODE -ne 0) { throw "Portable reference Create failed: $createdJson" }
    $created = $createdJson | ConvertFrom-Json
    $livePath = $created.result.output.assets[0].path
    foreach ($vendorTarget in @('oplus.olive', 'samsung.motionphoto', 'vivo.motionphoto')) {
        $vendorJson = & $launcher convert --input $livePath --target $vendorTarget --output-dir (Join-Path $verify "vendor converted $vendorTarget")
        if ($LASTEXITCODE -ne 0) { throw "Portable reference vendor Convert failed ($vendorTarget): $vendorJson" }
        $vendor = ($vendorJson | ConvertFrom-Json).result
        if ($vendor.execution | Where-Object transcoded -eq $true) { throw 'Pure vendor Convert unexpectedly encoded media.' }
        $vendorPath = $vendor.output.assets[0].path
        $vendorValidation = & $launcher validate --input $vendorPath --layers Structure,Protocol
        if ($LASTEXITCODE -ne 0) { throw "Portable vendor validation failed: $vendorValidation" }
        $vendorVideoJson = & $launcher extract --input $vendorPath --output-dir (Join-Path $verify "vendor extracted $vendorTarget")
        if ($LASTEXITCODE -ne 0 -or (Get-FileHash $referenceVideo).Hash -ne (Get-FileHash ($vendorVideoJson | ConvertFrom-Json).result.output.assets[0].path).Hash) { throw 'Vendor Convert changed original reference video.' }
        $vendorBackJson = & $launcher convert --input $vendorPath --target google.motionphoto.v2 --output-dir (Join-Path $verify "vendor back $vendorTarget")
        if ($LASTEXITCODE -ne 0) { throw "Portable vendor-to-Google conversion failed: $vendorBackJson" }
        $vendorBack = ($vendorBackJson | ConvertFrom-Json).result
        if (([decimal]$created.result.keyPhoto.position.value * $vendorBack.keyPhoto.position.timescale) -ne
            ([decimal]$vendorBack.keyPhoto.position.value * $created.result.keyPhoto.position.timescale)) { throw 'Vendor roundtrip changed known source key.' }
        if ($vendorTarget -in @('vivo.motionphoto', 'oplus.olive')) {
            # Negative fixture: corrupt only the known Length literal in a Core-created carrier.
            $vendorDamaged = [IO.File]::ReadAllBytes($vendorPath)
            $literal = 'i:Length="' + (Get-Item $referenceVideo).Length.ToString([Globalization.CultureInfo]::InvariantCulture) + '"'
            $text = [Text.Encoding]::Latin1.GetString($vendorDamaged)
            $position = $text.IndexOf($literal, [StringComparison]::Ordinal)
            if ($position -lt 0 -or $text.IndexOf($literal, $position + 1, [StringComparison]::Ordinal) -ge 0) { throw "$vendorTarget repair fixture needs one exact known Length literal." }
            $digit = $position + 'i:Length="'.Length
            $vendorDamaged[$digit] = if ($vendorDamaged[$digit] -eq [byte][char]'1') { [byte][char]'2' } else { [byte][char]'1' }
            $damagedVivo = Join-Path $verify "damaged $vendorTarget length.jpg"
            [IO.File]::WriteAllBytes($damagedVivo, $vendorDamaged)
            $previewJson = & $launcher repair --input $damagedVivo
            if ($LASTEXITCODE -ne 0) { throw "Portable $vendorTarget Repair preview failed: $previewJson" }
            $preview = ($previewJson | ConvertFrom-Json).result
            if ($preview.proposedChanges.Count -ne 1 -or $preview.changesApplied.Count -ne 0 -or $null -ne $preview.operation) { throw "$vendorTarget Repair preview was not read-only." }
            $repairJson = & $launcher repair --input $damagedVivo --apply --strict --output-dir (Join-Path $verify "repaired $vendorTarget length")
            if ($LASTEXITCODE -ne 0) { throw "Portable $vendorTarget Repair apply failed: $repairJson" }
            $repair = ($repairJson | ConvertFrom-Json).result
            if ($repair.changesApplied.Count -ne 1 -or $repair.blocked.Count -ne 0) { throw "$vendorTarget Repair did not apply the sole proved length patch." }
            $vivoRepaired = $repair.operation.output.assets[0].path
            $extractJson = & $launcher extract --input $vivoRepaired --output-dir (Join-Path $verify "repaired $vendorTarget extracted")
            if ($LASTEXITCODE -ne 0 -or (Get-FileHash $referenceVideo).Hash -ne (Get-FileHash ($extractJson | ConvertFrom-Json).result.output.assets[0].path).Hash) { throw "$vendorTarget Repair changed source video." }
            $secondJson = & $launcher repair --input $vivoRepaired
            if ($LASTEXITCODE -ne 0 -or ($secondJson | ConvertFrom-Json).result.proposedChanges.Count -ne 0) { throw "$vendorTarget Repair is not idempotent." }
            Write-Host $(if ($vendorTarget -eq 'vivo.motionphoto') { 'PORTABLE_VIVO_REPAIR=SUCCESS' } else { 'PORTABLE_OPLUS_REPAIR=SUCCESS' })
        }
    }
    Write-Host 'PORTABLE_VENDOR_CONVERT_ROUNDTRIP=SUCCESS'
    # Negative fixture only: change one decimal digit without changing APP length or media bytes.
    # The valid original carrier was produced by Core; this is not a script-side protocol writer.
    $damaged = [IO.File]::ReadAllBytes($livePath)
    $videoLength = (Get-Item $referenceVideo).Length.ToString([Globalization.CultureInfo]::InvariantCulture)
    $needle = [Text.Encoding]::UTF8.GetBytes('i:Length="' + $videoLength + '"')
    $lengthMatches = [Collections.Generic.List[int]]::new()
    $offset = 0
    while ($offset -le $damaged.Length - $needle.Length) {
        $candidate = [Array]::IndexOf($damaged, $needle[0], $offset)
        if ($candidate -lt 0 -or $candidate -gt $damaged.Length - $needle.Length) { break }
        $equal = $true
        for ($j = 0; $j -lt $needle.Length; $j++) { if ($damaged[$candidate + $j] -ne $needle[$j]) { $equal = $false; break } }
        if ($equal) { $lengthMatches.Add($candidate) }
        $offset = $candidate + 1
    }
    if ($lengthMatches.Count -ne 1) { throw 'Portable repair fixture needs one exact known length literal.' }
    $digit = $lengthMatches[0] + [Text.Encoding]::UTF8.GetByteCount('i:Length="')
    $damaged[$digit] = if ($damaged[$digit] -eq [byte][char]'1') { [byte][char]'2' } else { [byte][char]'1' }
    $damagedPath = Join-Path $verify 'damaged directory length.jpg'
    [IO.File]::WriteAllBytes($damagedPath, $damaged)
    $previewJson = & $launcher repair --input $damagedPath
    if ($LASTEXITCODE -ne 0) { throw "Portable V2 repair preview failed: $previewJson" }
    $preview = ($previewJson | ConvertFrom-Json).result
    if ($preview.proposedChanges.Count -ne 1 -or $preview.changesApplied.Count -ne 0 -or $null -ne $preview.operation) { throw 'Portable V2 repair preview wrote output or did not prove exactly one field.' }
    $repairJson = & $launcher repair --input $damagedPath --apply --strict --output-dir (Join-Path $verify 'repaired directory length')
    if ($LASTEXITCODE -ne 0) { throw "Portable V2 repair apply failed: $repairJson" }
    $repair = ($repairJson | ConvertFrom-Json).result
    if ($repair.changesApplied.Count -ne 1 -or $repair.blocked.Count -ne 0) { throw 'Portable V2 repair did not apply the sole proved change.' }
    $repairedPath = $repair.operation.output.assets[0].path
    $repairedExtractJson = & $launcher extract --input $repairedPath --output-dir (Join-Path $verify 'repaired extracted')
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash $referenceVideo).Hash -ne (Get-FileHash ($repairedExtractJson | ConvertFrom-Json).result.output.assets[0].path).Hash) { throw 'Portable V2 repair changed the complete source video.' }
    $secondRepairJson = & $launcher repair --input $repairedPath
    if ($LASTEXITCODE -ne 0 -or ($secondRepairJson | ConvertFrom-Json).result.proposedChanges.Count -ne 0) { throw 'Portable V2 repair is not idempotent.' }
    Write-Host 'PORTABLE_GOOGLE_V2_REPAIR=SUCCESS'
    # Synthetic framing fixtures exported only by successful Core tests. They are not
    # real-device fixtures or decoder evidence; the real-HEVC suite is separate.
    $heicFixtures = Join-Path $repository 'core/build/portable-heic-fixtures'
    $manifestFile = Join-Path $heicFixtures 'manifest.txt'
    if (-not (Test-Path $manifestFile)) { throw 'Run :core:jvmTest before packaging: HEIC conformance fixtures are absent.' }
    $manifest = @{}
    foreach ($line in Get-Content $manifestFile) {
        $pair = $line.Split('=', 2)
        if ($pair.Count -ne 2 -or $manifest.ContainsKey($pair[0])) { throw 'Invalid HEIC fixture manifest.' }
        $manifest[$pair[0]] = $pair[1]
    }
    if ($manifest.scope -ne 'synthetic-protocol-framing-not-decoder-or-device-proof' -or -not $manifest.runId) { throw 'HEIC fixture provenance is missing.' }
    foreach ($file in @('primary.heic', 'motion.mp4', 'motion.heic', 'damaged.heic')) {
        if ($manifest[$file] -notmatch '^[0-9a-f]{64}$' -or (Get-FileHash (Join-Path $heicFixtures $file)).Hash.ToLowerInvariant() -ne $manifest[$file]) { throw "Stale/mixed HEIC fixture: $file" }
    }
    $heicPrimary = Join-Path $heicFixtures 'primary.heic'
    $heicVideo = Join-Path $heicFixtures 'motion.mp4'
    $heicOriginal = Join-Path $heicFixtures 'motion.heic'
    $heicCreatedJson = & $launcher create --image $heicPrimary --video $heicVideo --target google.motionphoto.v2 --profile heic --frame-index 0 --strict --output-dir (Join-Path $verify 'HEIC created')
    if ($LASTEXITCODE -ne 0) { throw "Portable HEIC Create failed: $heicCreatedJson" }
    $heicCreated = ($heicCreatedJson | ConvertFrom-Json).result
    $heicPath = $heicCreated.output.assets[0].path
    if ($heicCreated.output.assets[0].imageFormat -ne 'Heic' -or (Get-FileHash $heicPath).Hash -ne (Get-FileHash $heicOriginal).Hash) { throw 'Portable HEIC writer did not reproduce the proven fixture.' }
    $heicInspectJson = & $launcher inspect --input $heicPath
    if ($LASTEXITCODE -ne 0 -or ($heicInspectJson | ConvertFrom-Json).result.detection.primaryProtocol.profile.value -ne 'heic') { throw 'Portable HEIC content detection/inspection failed.' }
    $heicValidationJson = & $launcher validate --input $heicPath --layers Structure,Protocol
    if ($LASTEXITCODE -ne 0) { throw "Portable HEIC validation failed: $heicValidationJson" }
    foreach ($sameTarget in @('PreserveAsIs', 'Normalize')) {
        $heicConvertJson = & $launcher convert --input $heicPath --target google.motionphoto.v2 --profile heic --same-target $sameTarget --strict --output-dir (Join-Path $verify "HEIC convert $sameTarget")
        if ($LASTEXITCODE -ne 0) { throw "Portable HEIC $sameTarget failed: $heicConvertJson" }
        $heicConverted = ($heicConvertJson | ConvertFrom-Json).result
        if ($heicConverted.execution | Where-Object { $_.transcoded -or $_.remuxed }) { throw 'Portable HEIC Convert unexpectedly encoded/remuxed media.' }
        if ($sameTarget -eq 'PreserveAsIs' -and (Get-FileHash $heicConverted.output.assets[0].path).Hash -ne (Get-FileHash $heicPath).Hash) { throw 'HEIC PreserveAsIs is not byte-identical.' }
        $heicRawJson = & $launcher extract --input $heicConverted.output.assets[0].path --output-dir (Join-Path $verify "HEIC raw $sameTarget")
        if ($LASTEXITCODE -ne 0 -or (Get-FileHash ($heicRawJson | ConvertFrom-Json).result.output.assets[0].path).Hash -ne (Get-FileHash $heicVideo).Hash) { throw 'Portable HEIC Convert changed the complete movie.' }
    }
    $heicCleanJson = & $launcher split --input $heicPath --strict --output-dir (Join-Path $verify 'HEIC clean')
    if ($LASTEXITCODE -ne 0) { throw "Portable HEIC Clean failed: $heicCleanJson" }
    $heicClean = ($heicCleanJson | ConvertFrom-Json).result
    $heicCleanImage = ($heicClean.output.assets | Where-Object role -eq 'PrimaryImage').path
    $heicCleanVideo = ($heicClean.output.assets | Where-Object role -eq 'MotionVideo').path
    if ($heicClean.output.assets.Count -ne 2 -or (Get-FileHash $heicCleanVideo).Hash -ne (Get-FileHash $heicVideo).Hash) { throw 'Portable HEIC Clean did not publish the complete exact asset set.' }
    $heicAgainJson = & $launcher split --input $heicCleanImage --strict --output-dir (Join-Path $verify 'HEIC clean again')
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash ($heicAgainJson | ConvertFrom-Json).result.output.assets[0].path).Hash -ne (Get-FileHash $heicCleanImage).Hash) { throw 'Portable HEIC Clean is not idempotent.' }
    $heicKeyJson = & $launcher set-key --input $heicPath --frame-index 1 --strict --output-dir (Join-Path $verify 'HEIC key metadata')
    if ($LASTEXITCODE -ne 0 -or ($heicKeyJson | ConvertFrom-Json).result.keyPhoto.position.value -ne '40000') { throw "Portable HEIC SetKey failed: $heicKeyJson" }
    $heicKeyPath = ($heicKeyJson | ConvertFrom-Json).result.output.assets[0].path
    $heicKeyCleanJson = & $launcher split --input $heicKeyPath --strict --output-dir (Join-Path $verify 'HEIC key cleaned')
    if ($LASTEXITCODE -ne 0) { throw 'Portable HEIC SetKey cleanup failed.' }
    $heicKeyClean = ($heicKeyCleanJson | ConvertFrom-Json).result
    if ((Get-FileHash ($heicKeyClean.output.assets | Where-Object role -eq 'PrimaryImage').path).Hash -ne (Get-FileHash $heicCleanImage).Hash -or
        (Get-FileHash ($heicKeyClean.output.assets | Where-Object role -eq 'MotionVideo').path).Hash -ne (Get-FileHash $heicVideo).Hash) { throw 'Portable HEIC SetKey changed primary coding or movie bytes.' }
    $heicDamaged = Join-Path $heicFixtures 'damaged.heic'
    $heicPreviewJson = & $launcher repair --input $heicDamaged
    if ($LASTEXITCODE -ne 0) { throw "Portable HEIC repair preview failed: $heicPreviewJson" }
    $heicPreview = ($heicPreviewJson | ConvertFrom-Json).result
    if ($heicPreview.proposedChanges.Count -ne 1 -or $heicPreview.changesApplied.Count -ne 0 -or $null -ne $heicPreview.operation) { throw 'HEIC repair preview was not read-only.' }
    $heicRepairJson = & $launcher repair --input $heicDamaged --apply --strict --output-dir (Join-Path $verify 'HEIC repaired')
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash ($heicRepairJson | ConvertFrom-Json).result.operation.output.assets[0].path).Hash -ne (Get-FileHash $heicOriginal).Hash) { throw "Portable HEIC length-only repair failed: $heicRepairJson" }
    foreach ($file in @('primary.heic', 'motion.mp4', 'motion.heic', 'damaged.heic')) {
        if ((Get-FileHash (Join-Path $heicFixtures $file)).Hash.ToLowerInvariant() -ne $manifest[$file]) { throw "Portable CLI mutated input: $file" }
    }
    Write-Host 'PORTABLE_GOOGLE_HEIC_CONFORMANCE=SUCCESS scope=synthetic-protocol-not-device-or-decode'
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
