$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$directory = Join-Path (Get-Location) 'file-io/build/io-link-fixtures'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$target = Join-Path $directory 'target.bin'
$link = Join-Path $directory 'link.bin'
# CI-owned fixtures only. A real link is mandatory: no silent test skip or ordinary-file substitute.
[IO.File]::WriteAllBytes($target, [byte[]]@(1, 2, 3))
New-Item -ItemType SymbolicLink -Path $link -Target $target | Out-Null
Write-Host 'FILE_IO_LINK_FIXTURE=READY'
