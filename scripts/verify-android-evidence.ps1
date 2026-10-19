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
$hostTests = Test-Reports 'android/build/test-results/testDebugUnitTest' 22
$releaseHostTests = Test-Reports 'android/build/test-results/testReleaseUnitTest' 22
$deviceTests = Test-Reports 'android/build/outputs/androidTest-results/connected' 24
$compile = Get-Content 'ci-output/android-build.log' -Raw
$device = Get-Content 'ci-output/android-device.log' -Raw
if ($compile -notmatch 'BUILD SUCCESSFUL' -or $device -notmatch 'BUILD SUCCESSFUL' -or
    $compile -notmatch 'Android projects: \[:, :android, :shared, :ui\]' -or $compile -notmatch 'android:androidJvm' -or
    $compile -notmatch ':android:testDebugUnitTest(?:\s|$)' -or
    $compile -match ':android:testDebugUnitTest (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)' -or
    $compile -notmatch ':android:testReleaseUnitTest(?:\s|$)' -or
    $compile -match ':android:testReleaseUnitTest (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)' -or
    $compile -notmatch ':android:minifyReleaseWithR8(?:\s|$)' -or
    $compile -match ':android:minifyReleaseWithR8 (SKIPPED|NO-SOURCE)' -or
    $device -notmatch ':android:connectedDebugAndroidTest(?:\s|$)' -or
    $device -match ':android:connectedDebugAndroidTest (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)') {
    throw 'Android graph or fresh task execution proof missing.'
}
$mappingPath = 'android/build/outputs/mapping/release/mapping.txt'
$mapping = Get-Content -LiteralPath $mappingPath -Raw
if ($mapping -notmatch '(?m)^# compiler: R8\s*$' -or
    $mapping -notmatch '(?m)^livephoto\.core\.[^\r\n]+ -> [^\r\n]+:$') {
    throw 'Release R8 mapping is missing compiler or shared Core evidence.'
}
# Keep optimized names instead of retaining the entire Core for DEX checks.
$renamedCore = @([regex]::Matches($mapping, '(?m)^(livephoto\.core\.[^\r\n]+) -> ([^\r\n]+):$') |
    Where-Object { $_.Groups[1].Value -ne $_.Groups[2].Value }).Count
if ($renamedCore -eq 0) { throw 'Release Core classes were not obfuscated.' }
$mappingHash = (Get-FileHash -LiteralPath $mappingPath -Algorithm SHA256).Hash.ToLowerInvariant()
$testApk = 'android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk'
$signer = Join-Path $env:ANDROID_HOME $(if ($IsWindows) { 'build-tools/37.0.0/apksigner.bat' } else { 'build-tools/37.0.0/apksigner' })
$aapt = Join-Path $env:ANDROID_HOME $(if ($IsWindows) { 'build-tools/37.0.0/aapt2.exe' } else { 'build-tools/37.0.0/aapt2' })
$apks = @()
New-Item -ItemType Directory -Path 'ci-output/apks' -Force | Out-Null
foreach ($variant in @('debug', 'release')) {
    $apk = "android/build/outputs/apk/$variant/android-$variant.apk"
    $zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $apk))
    try {
        $entries = @($zip.Entries | ForEach-Object { $_.FullName })
        $unexpectedLibraries = @($entries | Where-Object {
            $_ -match '^lib/.+\.so$' -and $_ -notmatch '^lib/(arm64-v8a|armeabi-v7a|x86|x86_64)/libandroidx\.graphics\.path\.so$'
        })
        if ('classes.dex' -notin $entries -or $unexpectedLibraries.Count -ne 0 -or $entries -match '^assets/reference/') {
            throw 'Application must contain DEX, no Core JNI or test media; only official Compose graphics native libraries are allowed.'
        }
    } finally { $zip.Dispose() }
    & $signer verify --verbose --print-certs $apk | Set-Content "ci-output/android-$variant-signature.txt"
    if ($LASTEXITCODE -ne 0) { throw "$variant signature verification failed." }
    $signature = Get-Content "ci-output/android-$variant-signature.txt" -Raw
    if ($signature -notmatch 'certificate SHA-256 digest: ([0-9a-fA-F]{64})') { throw 'Missing signer certificate digest.' }
    $certificate = $Matches[1].ToLowerInvariant()
    if ($env:LIVEPHOTO_EXPECTED_SIGNER -and $certificate -ne $env:LIVEPHOTO_EXPECTED_SIGNER) {
        throw 'APK is not signed with the requested persistent key.'
    }
    $badging = (& $aapt dump badging $apk) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='com\.ohyooo\.livephoto' versionCode='(\d+)'") {
        throw 'Missing application package identity.'
    }
    $versionCode = [int]$Matches[1]
    if ($versionCode -ne [int]$env:LIVEPHOTO_VERSION_CODE -or
        (($badging -match '(?m)^application-debuggable') -ne ($variant -eq 'debug'))) {
        throw 'Unexpected version code or debuggable flag.'
    }
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Copy-Item -LiteralPath $apk -Destination "ci-output/apks/LivePhoto-$variant.apk"
    $apks += [ordered]@{ variant = $variant; sha256 = $hash; bytes = (Get-Item $apk).Length; signerCertificateSha256 = $certificate; versionCode = $versionCode }
}
if ($apks[0].signerCertificateSha256 -ne $apks[1].signerCertificateSha256) { throw 'Debug and release must use the same signing certificate.' }
$install = Get-Content 'ci-output/android-install-evidence.json' -Raw | ConvertFrom-Json
if ($install.commit -ne $env:GITHUB_SHA -or "$($install.run)" -ne $env:GITHUB_RUN_ID -or
    $install.installs.Count -ne 2 -or $install.installs[0].variant -ne 'release' -or $install.installs[1].variant -ne 'debug' -or
    @($install.installs | Where-Object { -not $_.upgradedWithoutUninstall -or -not $_.launched }).Count -ne 0) {
    throw 'Both same-signature package upgrade/launch proofs are required.'
}
$summary = [ordered]@{
    commit = $env:GITHUB_SHA
    run = $env:GITHUB_RUN_ID
    minSdk = 29
    compileSdk = 37
    compileSdkMinor = 2
    deviceApi = 36
    hostTests = $hostTests
    releaseHostTests = $releaseHostTests
    deviceTests = $deviceTests
    skipped = 0
    failures = 0
    errors = 0
    apks = $apks
    testApkSha256 = (Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash.ToLowerInvariant()
    signerCertificateSha256 = $apks[0].signerCertificateSha256
    releaseOptimization = [ordered]@{
        compiler = 'R8'
        mappingSha256 = $mappingHash
        renamedCoreClasses = $renamedCore
    }
    signing = $(if ($env:LIVEPHOTO_SIGNING_KEYSTORE) { 'user-supplied-actions-secret' } else { 'generated-debug-key' })
    scope = 'android-app-direct-shared-core-saf-memory-media-store-single-jpeg-no-mobile-decoder-no-gallery-certification'
}
$summary | ConvertTo-Json -Depth 5 | Set-Content 'ci-output/android-evidence.json' -Encoding utf8
Write-Host "ANDROID_EVIDENCE=SUCCESS debugHost=$hostTests releaseHost=$releaseHostTests device=$deviceTests same-signature"
