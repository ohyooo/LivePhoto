param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9-]+$')]
    [string]$Phase,
    [string[]]$GradleTasks = @(':core:assemble', ':core:jvmTest')
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force -Path .validation | Out-Null
$startedUtc = [DateTime]::UtcNow
$logPath = ".validation\$Phase-gradle.log"
$summaryPath = ".validation\$Phase-summary.json"
$transcriptPath = ".validation\$Phase-transcript.log"
$arguments = @($GradleTasks) + @('--console=plain', '--warning-mode=all', '--rerun-tasks')
$summary = [ordered]@{
    runId = [Guid]::NewGuid().ToString('N')
    phase = $Phase
    host = $env:COMPUTERNAME
    directory = (Get-Location).Path
    javaHome = $null
    command = '.\gradlew.bat ' + ($arguments -join ' ')
    startedUtc = $startedUtc.ToString('o')
    completedUtc = $null
    gradleExitCode = $null
    tests = 0
    failures = 0
    errors = 0
    skipped = 0
    failedTests = @()
    reports = @()
    result = 'RUNNING'
    failureReason = $null
}
# Replace any prior SUCCESS before preflight, including failed JDK/daemon-criteria checks.
$summary | ConvertTo-Json -Depth 6 | Set-Content $summaryPath -Encoding utf8
$transcriptStarted = $false
try {
    if (':core:jvmTest' -notin $GradleTasks) {
        throw 'A milestone gate must include :core:jvmTest.'
    }
    # Persistent SSH/tmux sessions can predate the installed JDK environment.
    $machineJava = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    $userJava = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    $env:JAVA_HOME = if ($userJava) { $userJava } else { $machineJava }
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
        [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
        throw 'JAVA_HOME must point to an existing JDK; automatic installation is prohibited.'
    }
    $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
    $summary.javaHome = $env:JAVA_HOME

    $daemonCriteria = Get-ChildItem -Path . -Recurse -Filter gradle-daemon-jvm.properties -File
    if ($daemonCriteria) {
        $daemonCriteria | ForEach-Object { Write-Host $_.FullName }
        throw 'Remove gradle-daemon-jvm.properties before any Gradle operation.'
    }

    Start-Transcript -Path $transcriptPath -Force
    $transcriptStarted = $true
    Write-Host "RunId: $($summary.runId)"
    Write-Host "Host: $($summary.host)"
    Write-Host "Directory: $($summary.directory)"
    Write-Host "JAVA_HOME: $env:JAVA_HOME"
    Write-Host "Command: $($summary.command)"
    & .\gradlew.bat @arguments 2>&1 | Tee-Object -FilePath $logPath
    $summary.gradleExitCode = $LASTEXITCODE

    # Only accept reports written during this run. Rerun-tasks prevents old/up-to-date
    # reports from standing in for actual milestone test execution.
    $testFiles = @(Get-ChildItem -Path core\build\test-results\jvmTest, cli\build\test-results\test -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTimeUtc -ge $startedUtc })
    foreach ($file in $testFiles) {
        [xml]$report = Get-Content $file.FullName -Raw
        $summary.tests += [int]$report.testsuite.tests
        $summary.failures += [int]$report.testsuite.failures
        $summary.errors += [int]$report.testsuite.errors
        $summary.skipped += [int]$report.testsuite.skipped
        $summary.reports += [ordered]@{ name = $file.Name; writtenUtc = $file.LastWriteTimeUtc.ToString('o') }
        foreach ($test in $report.testsuite.testcase) {
            if ($test.failure -or $test.error) {
                $summary.failedTests += "$($test.classname).$($test.name)"
            }
        }
    }
    if ($summary.gradleExitCode -ne 0 -or $summary.failures -gt 0 -or $summary.errors -gt 0 -or
        ($summary.tests - $summary.skipped) -le 0) {
        throw "Remote validation did not pass: Gradle=$($summary.gradleExitCode) tests=$($summary.tests) failures=$($summary.failures) errors=$($summary.errors)"
    }
    $summary.result = 'SUCCESS'
    Write-Host "VALIDATION_RESULT=SUCCESS tests=$($summary.tests) failures=$($summary.failures) errors=$($summary.errors) skipped=$($summary.skipped)"
} catch {
    $summary.result = 'FAILURE'
    $summary.failureReason = $_.Exception.Message
    Write-Host "VALIDATION_RESULT=FAILURE reason=$($summary.failureReason)"
    throw
} finally {
    $summary.completedUtc = [DateTime]::UtcNow.ToString('o')
    $summary | ConvertTo-Json -Depth 6 | Set-Content $summaryPath -Encoding utf8
    if ($transcriptStarted) { Stop-Transcript }
}
