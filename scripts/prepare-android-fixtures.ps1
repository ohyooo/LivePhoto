$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$destination = 'android/build/test-assets/reference'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$fixtures = @{
    'video.jpg' = '18d7fe28bff459d41fb75fe8358bb6df0f7c5164c04158e8b120789af7da76a9'
    'video.mp4' = 'd334d2c36a821dfa82c2d0f1bb09948125596bf77fd8eff7034ae6c53b739b64'
    'livephoto.jpg' = 'fc4be53da351b60fa22086d9816871ab3fcec01ec9e166a72fbf82a3f6ba96cb'
}
foreach ($name in $fixtures.Keys) {
    $source = Join-Path 'reference' $name
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $fixtures[$name]) { throw 'Reference fixture changed.' }
    $path = Join-Path $destination $name
    if (Test-Path -LiteralPath $path) {
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $fixtures[$name]) { throw 'Unknown prepared fixture; refusing to overwrite.' }
    } else { Copy-Item -LiteralPath $source -Destination $path }
}
Write-Host 'ANDROID_TEST_FIXTURES=READY; originals unchanged, test APK only'
