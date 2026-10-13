$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$source = Join-Path (Get-Location) 'core/src/jvmTest/resources/protocol-compatibility/v1'
$destination = Join-Path (Get-Location) 'core/build/protocol-fixtures/v1'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
New-Item -ItemType Directory -Path 'core/build/reports/protocol-compatibility' -Force | Out-Null
New-Item -ItemType Directory -Path 'core/build/portable-heic-fixtures','core/build/reports/huawei-heic-fixtures' -Force | Out-Null
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
