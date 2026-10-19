$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$source = 'shared/build/dist/js/productionLibrary'
if (-not (Test-Path $source)) { throw 'Missing production JS library distribution.' }
$destination = 'ci-output/web-library'
if (Test-Path $destination) { throw 'Use a fresh Web package directory.' }
New-Item -ItemType Directory -Path $destination | Out-Null
Copy-Item "$source/*" -Destination $destination -Recurse
$module = @(Get-ChildItem $destination -Recurse -File -Filter 'livephoto-core.mjs')
if ($module.Count -ne 1 -or $module[0].Directory.FullName -ne (Resolve-Path $destination).Path) {
    throw 'Expected one flat livephoto-core.mjs entry.'
}
if (@(Get-ChildItem $destination -Recurse -File | Where-Object { $_.Name -match '\.d\.(mts|ts)$' }).Count -eq 0) { throw 'Missing generated TypeScript declarations.' }
Copy-Item scripts/web/livephoto.mjs $destination
Copy-Item scripts/web/livephoto.d.mts $destination
$files = @(Get-ChildItem $destination -Recurse -File)
if ($files | Where-Object { $_.Extension -notin @('.mjs', '.js', '.mts', '.ts', '.json', '.map') }) {
    throw 'Web library must contain code/declarations only, not media or native libraries.'
}
[ordered]@{
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    scope = 'kotlin-js-library-browser-file-blob-optional-media-backend'
    files = @($files | ForEach-Object {
        [ordered]@{
            path = [IO.Path]::GetRelativePath((Resolve-Path $destination).Path, $_.FullName).Replace('\', '/')
            bytes = $_.Length
            sha256 = (Get-FileHash $_.FullName).Hash.ToLowerInvariant()
        }
    })
} | ConvertTo-Json -Depth 5 | Set-Content ci-output/web-package-evidence.json -Encoding utf8
Write-Host 'WEB_PACKAGE=READY; production library, JS File/Blob bridge and TypeScript declarations'
