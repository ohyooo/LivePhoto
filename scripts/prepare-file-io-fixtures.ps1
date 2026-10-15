$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$directory = Join-Path (Get-Location) 'shared/build/io-link-fixtures'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$target = Join-Path $directory 'target.bin'
$link = Join-Path $directory 'link.bin'
# CI-owned fixtures only. A real link is mandatory: no silent test skip or ordinary-file substitute.
$existingTarget = Get-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
if ($existingTarget) {
    if ($existingTarget.PSIsContainer -or $existingTarget.Length -ne 3 -or
        ([IO.File]::ReadAllBytes($target) -join ',') -ne '1,2,3') {
        throw 'Unknown existing fixture target; no overwrite is allowed.'
    }
} else {
    [IO.File]::WriteAllBytes($target, [byte[]]@(1, 2, 3))
}
$existingLink = Get-Item -LiteralPath $link -Force -ErrorAction SilentlyContinue
if ($existingLink) {
    $targets = @($existingLink.Target)
    if ($existingLink.LinkType -ne 'SymbolicLink' -or $targets.Count -ne 1 -or
        [IO.Path]::GetFullPath($targets[0]) -ne [IO.Path]::GetFullPath($target)) {
        throw 'Unknown existing fixture link; no replacement is allowed.'
    }
} else {
    New-Item -ItemType SymbolicLink -Path $link -Target $target | Out-Null
}
Write-Host 'FILE_IO_LINK_FIXTURE=READY'
