$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$directory = Join-Path $env:RUNNER_TEMP 'livephoto process 中文'
if (Test-Path -LiteralPath $directory) { throw 'Process fixture directory already exists.' }
New-Item -ItemType Directory -Path $directory | Out-Null
$executable = Join-Path $directory $(if ($IsWindows) { 'process fixture.exe' } else { 'process fixture' })
$compiler = $(if ($IsWindows) { (Get-Command gcc -ErrorAction Stop).Source } else { (Get-Command cc -ErrorAction Stop).Source })
$compiled = Join-Path (Get-Location) $(if ($IsWindows) { 'ci-output/process-fixture.exe' } else { 'ci-output/process-fixture' })
if (Test-Path -LiteralPath $compiled) { throw 'Compiled fixture already exists.' }
$arguments = @('-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', 'scripts/fixtures/process-fixture.c', '-o', $compiled)
if ($IsWindows) { $arguments += @('-municode', '-static') }
& $compiler @arguments
if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $compiled -PathType Leaf)) { throw 'Native process fixture compilation failed.' }
Copy-Item -LiteralPath $compiled -Destination $executable
"LIVEPHOTO_PROCESS_FIXTURE=$executable" | Add-Content $env:GITHUB_ENV
Write-Host 'Native process fixture compiled with the existing host C compiler.'
