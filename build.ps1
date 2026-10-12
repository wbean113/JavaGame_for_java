[CmdletBinding()]
param([switch]$SkipTests, [switch]$Clean, [switch]$UiTests)

$ErrorActionPreference = 'Stop'
$exitCode = 1
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
try {
    . (Join-Path $PSScriptRoot 'project-env.ps1')
    $jdk = Find-GameJdk
    $maven = Find-GameMaven
    $env:JAVA_HOME = $jdk
    $env:PATH = (Join-Path $jdk 'bin') + ';' + $oldPath
    $repository = Join-Path $PSScriptRoot '.m2'
    New-Item -ItemType Directory -Path $repository -Force | Out-Null
    $mavenArguments = @('-B', '-ntp', '-f', (Join-Path $PSScriptRoot 'pom.xml'), "-Dmaven.repo.local=$repository")
    if ($Clean) { $mavenArguments += 'clean' }
    $mavenArguments += 'package'
    if ($SkipTests) { $mavenArguments += '-DskipTests=true' }
    if ($UiTests) { $mavenArguments += '-DuiTests=true' }
    Write-Host "JDK: $jdk"
    Write-Host "Maven: $maven"
    Write-Host "Project dependency cache: $repository"
    & $maven @mavenArguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) {
        Write-Host 'Build complete: target\psychological-balance-1.0.0.jar and target\lib\'
        if ($SkipTests) { Write-Host 'Tests were skipped by request.' }
    }
} catch {
    Write-Host "Build failed: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
}
exit $exitCode
