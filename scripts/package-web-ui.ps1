$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$source = 'ui/build/dist/js/productionExecutable'
if (-not (Test-Path $source)) { throw 'Missing production Compose browser distribution.' }
$destination = 'ci-output/web-ui'
if (Test-Path $destination) { throw 'Use a fresh Web UI output directory.' }
New-Item -ItemType Directory -Path $destination | Out-Null
Copy-Item "$source/*" $destination -Recurse
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
