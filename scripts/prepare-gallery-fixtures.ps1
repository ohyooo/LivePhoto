param(
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,
    [string] $Ffmpeg = 'ffmpeg'
)

$ErrorActionPreference = 'Stop'
$encoder = (Get-Command $Ffmpeg -CommandType Application | Select-Object -First 1).Source
if (Test-Path -LiteralPath $OutputDirectory) {
    throw 'Choose a new output directory. This script never replaces existing files.'
}
$directory = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
$cover = Join-Path $directory 'cover.jpg'
$clip = Join-Path $directory 'clip.mp4'

# Match the Web create regression fixture: a blue still, a colorful moving pattern,
# fractional video timestamps, and a short 440 Hz mono tone.
& $encoder -nostdin -n -hide_banner -loglevel error -f lavfi -i 'color=c=blue:s=64x64' -frames:v 1 -threads 1 -update 1 $cover
if ($LASTEXITCODE -ne 0) { throw 'Failed to generate the JPEG fixture.' }
& $encoder -nostdin -n -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=64x64:rate=30000/1001' `
    -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 3 -c:v libx264 -threads 1 -pix_fmt yuv420p `
    -bf 0 -g 30 -c:a aac -use_editlist 0 -video_track_timescale 90000 -movflags '+faststart' `
    -map_metadata -1 -metadata:s:v 'encoder=' -fflags '+bitexact' -flags:v '+bitexact' -write_btrt 0 $clip
if ($LASTEXITCODE -ne 0) { throw 'Failed to generate the MP4 fixture.' }

Get-FileHash -Algorithm SHA256 -LiteralPath $cover, $clip | ForEach-Object {
    "$($_.Hash.ToLowerInvariant())  $([IO.Path]::GetFileName($_.Path))"
} | Set-Content -LiteralPath (Join-Path $directory 'SHA256SUMS') -Encoding ascii
Write-Host "Synthetic gallery inputs: $directory"
