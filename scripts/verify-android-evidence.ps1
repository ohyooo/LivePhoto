$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
function Test-Reports([string]$Directory, [int]$Expected) {
    $files = @(Get-ChildItem -LiteralPath $Directory -Recurse -File -Filter 'TEST-*.xml')
    if ($files.Count -eq 0) { throw 'Missing executed Android test reports.' }
    $count = 0
    foreach ($file in $files) {
        [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
        # Gradle host reports use testsuite; AGP device reports wrap it in testsuites.
        # Count leaf suites only, never the aggregate container as well.
        $suites = @($report.SelectNodes('//testsuite[not(descendant::testsuite)]'))
        if ($suites.Count -eq 0) { throw 'Missing Android test suites.' }
        foreach ($suite in $suites) {
            foreach ($attribute in @('tests', 'failures', 'errors', 'skipped')) {
                if (-not $suite.HasAttribute($attribute) -or
                    $suite.GetAttribute($attribute) -notmatch '^\d+$') {
                    throw "Missing or invalid Android report attribute: $attribute"
                }
            }
            if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0 -or
                @($suite.SelectNodes('testcase/skipped | testcase/failure | testcase/error')).Count -ne 0) {
                throw 'Android tests failed or skipped.'
            }
            $executed = @($suite.SelectNodes('testcase')).Count
            if ($executed -ne [int]$suite.tests) { throw 'Android report testcase count mismatch.' }
            $count += $executed
        }
    }
    if ($count -ne $Expected) { throw "Incomplete Android test execution: $count / $Expected" }
    return $count
}
$hostTests = Test-Reports 'android/build/test-results/testDebugUnitTest' 9
$deviceTests = Test-Reports 'android/build/outputs/androidTest-results/connected' 9
$compile = Get-Content 'ci-output/android-build.log' -Raw
$device = Get-Content 'ci-output/android-device.log' -Raw
if ($compile -notmatch 'BUILD SUCCESSFUL' -or $device -notmatch 'BUILD SUCCESSFUL' -or
    $compile -notmatch 'Android projects: \[:, :android, :core\]' -or $compile -notmatch 'android:androidJvm' -or
    $compile -notmatch ':android:testDebugUnitTest(?:\s|$)' -or
    $compile -match ':android:testDebugUnitTest (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)' -or
    $device -notmatch ':android:connectedDebugAndroidTest(?:\s|$)' -or
    $device -match ':android:connectedDebugAndroidTest (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)') {
    throw 'Android graph or fresh task execution proof missing.'
}
$apk = 'android/build/outputs/apk/debug/android-debug.apk'
$testApk = 'android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk'
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $apk))
try {
    $entries = @($zip.Entries | ForEach-Object { $_.FullName })
    if ('classes.dex' -notin $entries -or $entries -match '^lib/.+\.so$' -or $entries -match '^assets/reference/') {
        throw 'Application must contain DEX, no bundled native SO or reference media.'
    }
} finally { $zip.Dispose() }
$signer = Join-Path $env:ANDROID_HOME $(if ($IsWindows) { 'build-tools/37.0.0/apksigner.bat' } else { 'build-tools/37.0.0/apksigner' })
& $signer verify --verbose --print-certs $apk | Set-Content 'ci-output/android-signature.txt'
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$signature = Get-Content 'ci-output/android-signature.txt' -Raw
if ($signature -notmatch 'certificate SHA-256 digest: ([0-9a-fA-F]{64})') { throw 'Missing signer certificate digest.' }
$certificate = $Matches[1].ToLowerInvariant()
$summary = [ordered]@{
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    minSdk = 29
    compileSdk = 37
    compileSdkMinor = 2
    deviceApi = 36
    hostTests = $hostTests
    deviceTests = $deviceTests
    skipped = 0
    failures = 0
    errors = 0
    apkSha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    apkBytes = (Get-Item -LiteralPath $apk).Length
    testApkSha256 = (Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash.ToLowerInvariant()
    signerCertificateSha256 = $certificate
    signing = $(if ($env:LIVEPHOTO_SIGNING_KEYSTORE) { 'user-supplied-actions-secret' } else { 'generated-debug-key' })
    scope = 'android-app-direct-shared-core-saf-memory-media-store-single-jpeg-no-mobile-decoder-no-gallery-certification'
}
$summary | ConvertTo-Json -Depth 5 | Set-Content 'ci-output/android-evidence.json' -Encoding utf8
Write-Host "ANDROID_EVIDENCE=SUCCESS host=$hostTests device=$deviceTests"
