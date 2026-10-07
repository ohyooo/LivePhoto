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
        if ($vendorTarget -eq 'vivo.motionphoto') {
            # Negative fixture: corrupt only the known Length literal in a Core-created carrier.
            $vendorDamaged = [IO.File]::ReadAllBytes($vendorPath)
            $literal = 'i:Length="' + (Get-Item $referenceVideo).Length.ToString([Globalization.CultureInfo]::InvariantCulture) + '"'
            $text = [Text.Encoding]::Latin1.GetString($vendorDamaged)
            $position = $text.IndexOf($literal, [StringComparison]::Ordinal)
            if ($position -lt 0 -or $text.IndexOf($literal, $position + 1, [StringComparison]::Ordinal) -ge 0) { throw 'Vivo repair fixture needs one exact known Length literal.' }
            $digit = $position + 'i:Length="'.Length
            $vendorDamaged[$digit] = if ($vendorDamaged[$digit] -eq [byte][char]'1') { [byte][char]'2' } else { [byte][char]'1' }
            $damagedVivo = Join-Path $verify 'damaged vivo length.jpg'
            [IO.File]::WriteAllBytes($damagedVivo, $vendorDamaged)
            $previewJson = & $launcher repair --input $damagedVivo
            if ($LASTEXITCODE -ne 0) { throw "Portable vivo Repair preview failed: $previewJson" }
            $preview = ($previewJson | ConvertFrom-Json).result
            if ($preview.proposedChanges.Count -ne 1 -or $preview.changesApplied.Count -ne 0 -or $null -ne $preview.operation) { throw 'Vivo Repair preview was not read-only.' }
            $repairJson = & $launcher repair --input $damagedVivo --apply --strict --output-dir (Join-Path $verify 'repaired vivo length')
            if ($LASTEXITCODE -ne 0) { throw "Portable vivo Repair apply failed: $repairJson" }
            $repair = ($repairJson | ConvertFrom-Json).result
            if ($repair.changesApplied.Count -ne 1 -or $repair.blocked.Count -ne 0) { throw 'Vivo Repair did not apply the sole proved length patch.' }
            $vivoRepaired = $repair.operation.output.assets[0].path
            $extractJson = & $launcher extract --input $vivoRepaired --output-dir (Join-Path $verify 'repaired vivo extracted')
            if ($LASTEXITCODE -ne 0 -or (Get-FileHash $referenceVideo).Hash -ne (Get-FileHash ($extractJson | ConvertFrom-Json).result.output.assets[0].path).Hash) { throw 'Vivo Repair changed source video.' }
            $secondJson = & $launcher repair --input $vivoRepaired
            if ($LASTEXITCODE -ne 0 -or ($secondJson | ConvertFrom-Json).result.proposedChanges.Count -ne 0) { throw 'Vivo Repair is not idempotent.' }
            Write-Host 'PORTABLE_VIVO_REPAIR=SUCCESS'
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
