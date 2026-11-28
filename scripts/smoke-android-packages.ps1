$ErrorActionPreference = 'Stop'
# Check native exit codes ourselves so adb's actual failure is kept in CI logs.
$PSNativeCommandUseErrorActionPreference = $false
Set-Location (Split-Path $PSScriptRoot -Parent)
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb'
$package = 'com.ohyooo.livephoto'
$evidence = [ordered]@{ commit = $env:GITHUB_SHA; run = $env:GITHUB_RUN_ID; installs = @() }
function Install-TestApk([string]$Variant, [string]$Operation) {
    $apk = "android/build/outputs/apk/$Variant/android-$Variant.apk"
    $installed = (& $adb install -r $apk 2>&1) -join "`n"
    $installExit = $LASTEXITCODE
    if ($installExit -ne 0 -or $installed -notmatch '(?m)^Success\s*$') {
        $detail = ($installed -split '\r?\n' | Select-Object -Last 8 | ForEach-Object {
            $_.Substring(0, [Math]::Min(400, $_.Length))
        }) -join "`n"
        throw "Cannot $Operation $Variant APK (adb exit $installExit).`n$detail"
    }
}

# AGP may uninstall the tested app when instrumentation finishes. Explicitly
# seed the debug install, so release really replaces debug rather than a blank slot.
Install-TestApk 'debug' 'install baseline'
$evidence.baselineDebugInstalled = $true
foreach ($variant in @('release', 'debug')) {
    & $adb shell am force-stop $package
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop the test application.' }
    Install-TestApk $variant 'upgrade to'
    $launch = (& $adb shell am start -W -n "$package/livephoto.android.MainActivity" 2>&1) -join "`n"
    $launchExit = $LASTEXITCODE
    $launch | Set-Content "ci-output/android-$variant-launch.log" -Encoding utf8
    if ($launchExit -ne 0 -or $launch -notmatch '(?m)^Status: ok\s*$') { throw "$variant Activity launch failed." }
    Start-Sleep -Seconds 2
    $process = (& $adb shell pidof -s $package 2>&1) -join ''
    if ($LASTEXITCODE -ne 0 -or $process -notmatch '^\d+\s*$') { throw "$variant application exited after launch." }
    $evidence.installs += [ordered]@{ variant = $variant; upgradedWithoutUninstall = $true; launched = $true }
    $remote = '/data/local/tmp/livephoto-' + [guid]::NewGuid().ToString('N') + '.png'
    & $adb shell screencap -p $remote
    if ($LASTEXITCODE -ne 0) { throw 'Cannot capture UI evidence.' }
    & $adb pull $remote "ci-output/android-$variant-screen.png"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot download UI evidence.' }
    & $adb shell rm $remote
    if ($LASTEXITCODE -ne 0) { throw 'Cannot remove test-owned emulator screenshot.' }
}
# Seed test-only files in the debuggable application's private cache, then
# restart the same install. Force-stop leaves private files in place.
function Invoke-PrivateCacheCheck([string]$Command, [string]$Failure) {
    & $adb shell "run-as $package sh -c '$Command'"
    if ($LASTEXITCODE -ne 0) { throw $Failure }
}
$snapshot = 'cache/livephoto-input-370b7f8b-d796-45fe-9642-114bc23169be.snapshot'
$stage = 'cache/livephoto-output-7205162290387421361.stage'
$control = 'cache/livephoto-restart-test-control'
Invoke-PrivateCacheCheck "mkdir -p cache && echo snapshot > $snapshot && echo stage > $stage && echo keep > $control" 'Cannot seed private recovery fixtures.'
& $adb shell am force-stop $package
if ($LASTEXITCODE -ne 0) { throw 'Cannot stop LivePhoto for private cache recovery.' }
Invoke-PrivateCacheCheck "test -s $snapshot && test -s $stage && test -s $control" 'Recovery fixtures did not survive force-stop.'
$restart = (& $adb shell am start -W -n "$package/livephoto.android.MainActivity" 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $restart -notmatch '(?m)^Status: ok\s*$') { throw 'LivePhoto restart failed.' }
Invoke-PrivateCacheCheck "test ! -e $snapshot && test ! -e $stage && grep -qx keep $control && rm $control" 'Cold start did not reclaim only the owned recovery fixtures.'
$evidence.privateCacheRecoveredAfterRestart = $true
$evidence | ConvertTo-Json -Depth 5 | Set-Content 'ci-output/android-install-evidence.json' -Encoding utf8
Write-Host 'ANDROID_INSTALL_EVIDENCE=SUCCESS debug-to-release-to-debug'
