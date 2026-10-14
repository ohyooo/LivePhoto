$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if ($IsWindows) {
    # Maintained stable 9.0 release branch, not the master/nightly build.
    # CI dependency only: never bundled or downloaded by the application.
    $zip = Join-Path $env:RUNNER_TEMP 'ffmpeg-stable.zip'
    $directory = Join-Path $env:RUNNER_TEMP 'livephoto-ffmpeg-stable'
    Invoke-WebRequest 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n9.0-latest-win64-gpl-9.0.zip' -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $directory
    $binaries = @(Get-ChildItem $directory -Recurse -File -Filter ffmpeg.exe)
    if ($binaries.Count -ne 1) { throw 'Missing or ambiguous FFmpeg executable.' }
    $ffmpeg = $binaries[0].FullName
} else {
    if ($IsLinux) {
        $brew = '/home/linuxbrew/.linuxbrew/bin/brew'
    } else { $brew = (Get-Command brew -ErrorAction Stop).Source }
    & $brew update
    if ($LASTEXITCODE -ne 0) { throw 'Homebrew update failed.' }
    & $brew list --versions ffmpeg
    if ($LASTEXITCODE -eq 0) { & $brew upgrade ffmpeg } else { & $brew install ffmpeg }
    if ($LASTEXITCODE -ne 0) { throw 'Stable FFmpeg provisioning failed.' }
    $prefix = & $brew --prefix ffmpeg
    if ($LASTEXITCODE -ne 0) { throw 'Missing FFmpeg formula prefix.' }
    $ffmpeg = Join-Path $prefix 'bin/ffmpeg'
}
& $ffmpeg -version | Set-Content ci-output/native-ffmpeg-version.txt
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg version check failed.' }
$encoders = (& $ffmpeg -hide_banner -encoders 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $encoders -notmatch 'libx264' -or $encoders -notmatch 'libx265' -or $encoders -notmatch '\baac\b') { throw 'Required fixture encoders unavailable.' }
"LIVEPHOTO_FFMPEG=$ffmpeg" | Add-Content $env:GITHUB_ENV
Write-Host 'Stable external FFmpeg available for mandatory Native validation.'
