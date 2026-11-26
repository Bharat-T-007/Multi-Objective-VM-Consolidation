param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
if (-not (Test-Path (Join-Path $toolRoot 'jdk'))) {
    $archivePath = Join-Path $toolRoot 'jdk.zip'
    if (-not (Test-Path $archivePath)) {
        Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $archivePath
    }
    Expand-Archive -LiteralPath $archivePath -DestinationPath (Join-Path $toolRoot 'java-extracted') -Force
    $jdkFolder = Get-ChildItem -LiteralPath (Join-Path $toolRoot 'java-extracted') -Directory | Select-Object -First 1
    $resolvedSource = $jdkFolder.FullName
    $resolvedDestination = [IO.Path]::GetFullPath((Join-Path $toolRoot 'jdk'))
    if (-not $resolvedSource.StartsWith($toolRoot + [IO.Path]::DirectorySeparatorChar) -or -not $resolvedDestination.StartsWith($toolRoot + [IO.Path]::DirectorySeparatorChar)) { throw 'Tool paths must remain inside .tools' }
    Move-Item -LiteralPath $resolvedSource -Destination $resolvedDestination
}
if (-not (Test-Path (Join-Path $toolRoot 'apache-maven-3.9.9'))) {
    $archivePath = Join-Path $toolRoot 'maven.zip'
    if (-not (Test-Path $archivePath)) {
        Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip' -OutFile $archivePath
    }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolRoot -Force
}
Write-Output 'Local Java and Maven are ready in .tools.'

