$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

# Refresh the environment inherited by the persistent SSH/tmux session.
$machineJava = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
$userJava = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
$env:JAVA_HOME = if ($userJava) { $userJava } else { $machineJava }
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
    [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    throw 'JAVA_HOME must point to an existing JDK; automatic installation is prohibited.'
}
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

$daemonCriteria = Get-ChildItem -Path . -Recurse -Filter gradle-daemon-jvm.properties -File
if ($daemonCriteria) {
    $daemonCriteria | ForEach-Object { Write-Host $_.FullName }
    throw 'Remove gradle-daemon-jvm.properties before any Gradle operation.'
}

New-Item -ItemType Directory -Force -Path .validation | Out-Null
Start-Transcript -Path .validation\gradle-bootstrap.log -Force
try {
    Write-Host "Host: $env:COMPUTERNAME"
    Write-Host "Directory: $(Get-Location)"
    Write-Host "JAVA_HOME: $env:JAVA_HOME"
    & "$env:JAVA_HOME\bin\java.exe" -version
    if ($LASTEXITCODE -ne 0) { throw "java failed: $LASTEXITCODE" }

    Write-Host 'Command: .\gradlew.bat --version'
    & .\gradlew.bat --version 2>&1 | Tee-Object -FilePath .validation\gradle-version.log
    if ($LASTEXITCODE -ne 0) { throw "Wrapper initialization failed: $LASTEXITCODE" }

    Write-Host 'Command: .\gradlew.bat help :core:assemble :core:jvmTest --console=plain'
    & .\gradlew.bat help :core:assemble :core:jvmTest --console=plain 2>&1 |
        Tee-Object -FilePath .validation\gradle-scaffold.log
    if ($LASTEXITCODE -ne 0) { throw "Scaffold validation failed: $LASTEXITCODE" }
    Write-Host 'BOOTSTRAP_RESULT=SUCCESS'
} finally {
    Stop-Transcript
}
