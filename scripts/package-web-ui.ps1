$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$source = 'ui/build/dist/js/productionExecutable'
if (-not (Test-Path $source)) { throw 'Missing production Compose browser distribution.' }
$destination = 'ci-output/web-ui'
if (Test-Path $destination) { throw 'Use a fresh Web UI output directory.' }
New-Item -ItemType Directory -Path $destination | Out-Null
Copy-Item "$source/*" $destination -Recurse
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
if ($files | Where-Object { $_.Extension -match '^\.(jpg|jpeg|mp4|mov|heic|heif|apk|klib)$' }) { throw 'Web UI package contains media/test inputs.' }
[ordered]@{
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    scope = 'shared-compose-screen-browser-picker-local-core-download-no-server-processing'
    files = @($files | ForEach-Object {
        [ordered]@{
            path = [IO.Path]::GetRelativePath((Resolve-Path $destination).Path, $_.FullName).Replace('\', '/')
            bytes = $_.Length
            sha256 = (Get-FileHash $_.FullName).Hash.ToLowerInvariant()
        }
    })
} | ConvertTo-Json -Depth 5 | Set-Content ci-output/web-ui-package-evidence.json -Encoding utf8
