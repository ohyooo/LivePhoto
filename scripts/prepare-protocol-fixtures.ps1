$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$source = Join-Path (Get-Location) 'core/src/commonTest/resources/protocol-compatibility/v1'
$destination = Join-Path (Get-Location) 'core/build/protocol-fixtures/v1'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
New-Item -ItemType Directory -Path 'core/build/reports/protocol-compatibility' -Force | Out-Null
New-Item -ItemType Directory -Path 'core/build/portable-heic-fixtures','core/build/reports/huawei-heic-fixtures' -Force | Out-Null
New-Item -ItemType Directory -Path 'core/build/portable-apple-heif-fixtures','core/build/portable-apple-ordinary-exif-fixtures',
    'core/build/reports/samsung-remux-fixtures','core/build/reports/vivo-remux-fixtures','core/build/reports/huawei-remux-fixtures',
    'core/build/encoded-media-fixtures' -Force | Out-Null
foreach ($file in Get-ChildItem -LiteralPath $source -File) {
    $name = $file.Name
    $bytes = $null
    if ($name.EndsWith('.fixture.gz')) {
        $name = $name.Substring(0, $name.Length - 3)
        $input = [IO.File]::OpenRead($file.FullName)
        try {
            $gzip = [IO.Compression.GZipStream]::new($input, [IO.Compression.CompressionMode]::Decompress)
            try {
                $buffer = [byte[]]::new(65536)
                $output = [IO.MemoryStream]::new()
                try {
                    while (($count = $gzip.Read($buffer, 0, $buffer.Length)) -gt 0) {
                        if ($output.Length + $count -gt 32000000) { throw 'Expanded fixture exceeds limit.' }
                        $output.Write($buffer, 0, $count)
                    }
                    $bytes = $output.ToArray()
                } finally { $output.Dispose() }
            } finally { $gzip.Dispose() }
        } finally { $input.Dispose() }
    } elseif ($name -in @('captures.tsv', 'user-photos.tsv', 'asset-manifest.json')) {
        $bytes = [IO.File]::ReadAllBytes($file.FullName)
    } else { throw 'Unexpected protocol fixture entry.' }
    $path = Join-Path $destination $name
    if (Test-Path -LiteralPath $path) {
        $existing = [IO.File]::ReadAllBytes($path)
        if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($existing)) -ne
            [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))) {
            throw 'Existing prepared fixture differs; no overwrite is allowed.'
        }
    } else { [IO.File]::WriteAllBytes($path, $bytes) }
}
Write-Host 'PROTOCOL_FIXTURES=READY'
# Test-only expansion of existing immutable resources; no application decoder or new media profile.
$encoded = @{
    'remux-main' = @('windows-media/remux-main.mp4.base64', 'be48fea2d56bb357d2f7370f3efc9462171d4d47d61451d35b3a8c9d78cc56e6')
    'frame-high-mov' = @('windows-media/frame-high.mov.base64', '5929ddf215c236013abb1dff65c2c3537a878fe4c2ad40fa0af7e46ada2efe19')
    'ordinary-text-mov' = @('apple-media/ordinary-text.mov.base64', '9d2be28350c0c807ac7164c80968ef75ce875fb8d6da04b2a3d89165c82aa6a4')
    'ordinary-mdta-mp4' = @('apple-media/ordinary-mdta.mp4.base64', '0d32b1e5e477152a1b31e4e57ccd3ffc0bc1be725d0dc4a1f339b6b3d2956e2c')
}
foreach ($name in $encoded.Keys) {
    $resource = Join-Path 'core/src/commonTest/resources' $encoded[$name][0]
    $text = [IO.File]::ReadAllText($resource, [Text.Encoding]::ASCII).Trim()
    if ($text.Length -gt 40000) { throw 'Encoded fixture exceeds bound.' }
    $bytes = [Convert]::FromBase64String($text)
    $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    if ($hash -ne $encoded[$name][1]) { throw 'Encoded fixture differs from fixed digest.' }
    $path = Join-Path 'core/build/encoded-media-fixtures' "$name.bin"
    if (Test-Path -LiteralPath $path) {
        if ((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -ne $hash) { throw 'Existing encoded fixture differs; no overwrite allowed.' }
    } else { [IO.File]::WriteAllBytes($path, $bytes) }
}
Write-Host 'ENCODED_MEDIA_FIXTURES=READY'
