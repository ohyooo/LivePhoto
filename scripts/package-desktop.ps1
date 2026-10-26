param([Parameter(Mandatory)][ValidateSet('windows-x64','linux-x64','macos-arm64')][string]$Platform)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$root = Join-Path (Get-Location) 'desktop/build/compose/binaries/main/app'
if (-not (Test-Path -LiteralPath $root)) { throw 'Portable application missing.' }
$relative = switch ($Platform) {
    'windows-x64' { 'LivePhoto/LivePhoto.exe' }
    'linux-x64' { 'LivePhoto/bin/LivePhoto' }
    'macos-arm64' { 'LivePhoto.app/Contents/MacOS/LivePhoto' }
}
$launcher = Join-Path $root $relative
if (-not (Test-Path -LiteralPath $launcher)) { throw 'Portable launcher missing.' }
$output = (& $launcher --help 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $output -notmatch 'LivePhoto Desktop') { throw 'Packaged launcher failed.' }
$runtime = @(Get-ChildItem $root -Recurse -File | Where-Object { $_.Name -in @('jvm.dll','libjvm.so','libjvm.dylib') })
if ($runtime.Count -ne 1) { throw 'Expected exactly one bundled JVM.' }
$groups = @{}
foreach ($entry in @(@('shared','desktopJvmTest'), @('ui','desktopTest'), @('desktop','desktopTest'))) {
    $module,$task = $entry
    $files = @(Get-ChildItem "$module/build/test-results/$task" -Filter 'TEST-*.xml' -File)
    if ($files.Count -eq 0) { throw "Missing $module test reports." }
    $tests = 0
    foreach ($file in $files) {
        [xml]$xml = Get-Content $file.FullName -Raw
        if ([int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0) { throw 'Failed or skipped desktop tests.' }
        $tests += [int]$xml.testsuite.tests
    }
    if ($tests -eq 0) { throw 'No tests executed.' }
    $groups[$module] = $tests
}
$directory = 'ci-output/desktop'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$filename = "LivePhoto-Desktop-$Platform"
if ($IsWindows) {
    $archive = "$directory/$filename.zip"
    Compress-Archive -Path (Join-Path $root '*') -DestinationPath $archive
} else {
    $archive = "$directory/$filename.tar.gz"
    & tar -czf $archive -C $root .
    if ($LASTEXITCODE -ne 0) { throw 'Desktop archive failed.' }
}
$files = @(Get-ChildItem $root -Recurse -File | ForEach-Object {
    @{ path = [IO.Path]::GetRelativePath($root, $_.FullName).Replace('\','/'); size = $_.Length; sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
@{
    schemaVersion = 1; sourceSha = $env:GITHUB_SHA; runId = $env:GITHUB_RUN_ID; runAttempt = $env:GITHUB_RUN_ATTEMPT
    platform = $Platform; launcher = $relative; bundledRuntime = $true; ffmpegBundled = $false
    tests = $groups; files = $files
    archive = @{ name = [IO.Path]::GetFileName($archive); size = (Get-Item $archive).Length; sha256 = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() }
    launcherHelp = $output.Trim()
} | ConvertTo-Json -Depth 10 | Set-Content "$directory/desktop-evidence.json"
Write-Host 'DESKTOP_PORTABLE=VERIFIED'
