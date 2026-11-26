param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArgs)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkPath = Join-Path $projectRoot '.tools/jdk'
$mavenPath = Join-Path $projectRoot '.tools/apache-maven-3.9.9/bin/mvn.cmd'
if (-not (Test-Path $mavenPath) -or -not (Test-Path $jdkPath)) { throw 'Run scripts/setup-tools.ps1 first.' }
$env:JAVA_HOME = $jdkPath
$env:PATH = (Join-Path $jdkPath 'bin') + [IO.Path]::PathSeparator + $env:PATH
Push-Location $projectRoot
try {
    & $mavenPath "-Dmaven.repo.local=$projectRoot/.m2" @MavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }

