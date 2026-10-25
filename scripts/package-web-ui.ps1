$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$source = 'ui/build/dist/wasmJs/productionExecutable'
if (-not (Test-Path $source)) { throw 'Missing production Compose browser distribution.' }
$destination = 'ci-output/web-ui'
if (Test-Path $destination) { throw 'Use a fresh Web UI output directory.' }
New-Item -ItemType Directory -Path $destination | Out-Null
Copy-Item "$source/*" $destination -Recurse
$worker = 'ui/worker/build/dist/wasmJs/productionExecutable'
if (-not (Test-Path "$worker/livephoto-worker.js")) { throw 'Missing Kotlin/Wasm UI worker.' }
New-Item -ItemType Directory -Path "$destination/worker" | Out-Null
Copy-Item "$worker/*" "$destination/worker" -Recurse
Copy-Item scripts/web/ui-task-runtime.mjs $destination
$media = Join-Path $destination 'media'
New-Item -ItemType Directory -Path $media -Force | Out-Null
Copy-Item scripts/web/media/*.mjs $media
$vendor = Join-Path $media 'vendor'
New-Item -ItemType Directory -Path $vendor -Force | Out-Null
Copy-Item scripts/web/media/THIRD-PARTY.txt $media
Copy-Item scripts/web/media/ffmpeg-wrapper-LICENSE.txt $media
Copy-Item scripts/web/media/ffmpeg-core-GPL-2.0.txt $media
New-Item -ItemType Directory -Path "$vendor/core" -Force | Out-Null
foreach ($file in @('ffmpeg-core.js', 'ffmpeg-core.wasm', 'build.json')) {
    if (-not (Test-Path ".web-media-engine/$file")) { throw "Missing rebuilt browser engine: $file" }
    Copy-Item ".web-media-engine/$file" "$vendor/core/$file" -Force
}
@('Dockerfile', 'preserve-channels.py') | ForEach-Object {
    "=== $_ ==="
    Get-Content "scripts/web/engine/$_"
} | Set-Content "$media/engine-source.txt" -Encoding utf8
# Noto's latest stable Sans CJK release; ship its unmodified font and OFL license.
# These public assets are fetched at packaging time, never at application runtime.
$fontDirectory = Join-Path $destination 'fonts'
New-Item -ItemType Directory -Path $fontDirectory | Out-Null
$font = Join-Path $fontDirectory 'NotoSansSC-Regular.otf'
Invoke-WebRequest 'https://raw.githubusercontent.com/notofonts/noto-cjk/Sans2.004/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf' -OutFile $font
if ((Get-FileHash $font).Hash.ToLowerInvariant() -ne 'faa6c9df652116dde789d351359f3d7e5d2285a2b2a1f04a2d7244df706d5ea9') { throw 'Noto font digest mismatch.' }
Invoke-WebRequest 'https://raw.githubusercontent.com/notofonts/noto-cjk/Sans2.004/LICENSE' -OutFile (Join-Path $fontDirectory 'OFL.txt')
if ((Get-FileHash (Join-Path $fontDirectory 'OFL.txt')).Hash.ToLowerInvariant() -ne '6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2') { throw 'Noto license digest mismatch.' }
foreach ($name in @('index.html', 'livephoto-ui.js')) {
    if (-not (Test-Path (Join-Path $destination $name))) { throw "Missing browser entry: $name" }
}
$files = @(Get-ChildItem $destination -Recurse -File)
$applicationWasm = @($files | Where-Object { $_.DirectoryName -eq (Resolve-Path $destination).Path -and $_.Name -match '^livephoto-ui\.[0-9a-f]+\.wasm$' })
if ($applicationWasm.Count -eq 0) { throw 'Missing Kotlin/Wasm application binary (Skiko/FFmpeg alone are not sufficient).' }
$workerWasm = @($files | Where-Object { $_.Name -match '^livephoto-worker\.[0-9a-f]+\.wasm$' })
if ($workerWasm.Count -eq 0) { throw 'Missing Kotlin/Wasm background computation binary.' }
if ($files | Where-Object { $_.Extension -match '^\.(jpg|jpeg|mp4|mov|heic|heif|apk|klib)$' }) { throw 'Web UI package contains media/test inputs.' }
[ordered]@{
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    scope = 'shared-compose-screen-browser-picker-local-core-download-no-server-processing'
    kotlinRuntime = 'wasmJs'
    applicationWasm = @($applicationWasm.Name)
    workerWasm = @($workerWasm | ForEach-Object { 'worker/' + $_.Name })
    files = @($files | ForEach-Object {
        [ordered]@{
            path = [IO.Path]::GetRelativePath((Resolve-Path $destination).Path, $_.FullName).Replace('\', '/')
            bytes = $_.Length
            sha256 = (Get-FileHash $_.FullName).Hash.ToLowerInvariant()
        }
    })
} | ConvertTo-Json -Depth 5 | Set-Content ci-output/web-ui-package-evidence.json -Encoding utf8
