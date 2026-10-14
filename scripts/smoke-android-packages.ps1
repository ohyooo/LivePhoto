$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb'
$package = 'com.ohyooo.livephoto'
$evidence = [ordered]@{ commit = $env:GITHUB_SHA; run = $env:GITHUB_RUN_ID; installs = @() }
foreach ($variant in @('release', 'debug')) {
    & $adb shell am force-stop $package
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop the test application.' }
    $apk = "android/build/outputs/apk/$variant/android-$variant.apk"
    $installed = (& $adb install -r $apk 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $installed -notmatch '(?m)^Success\s*$') { throw "Cannot upgrade to $variant APK." }
    $launch = (& $adb shell am start -W -n "$package/.MainActivity" 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $launch -notmatch '(?m)^Status: ok\s*$') { throw "$variant Activity launch failed." }
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
$evidence | ConvertTo-Json -Depth 5 | Set-Content 'ci-output/android-install-evidence.json' -Encoding utf8
Write-Host 'ANDROID_INSTALL_EVIDENCE=SUCCESS debug-to-release-to-debug'
