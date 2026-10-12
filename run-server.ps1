[CmdletBinding()]
param([ValidateRange(1, 65535)][int]$Port = 23456)

$ErrorActionPreference = 'Stop'
try {
    . (Join-Path $PSScriptRoot 'project-env.ps1')
    $jdk = Find-GameJdk
    $classpath = Get-GameClasspath
    Push-Location -LiteralPath $PSScriptRoot
    try {
        & (Join-Path $jdk 'bin\java.exe') '-Dfile.encoding=UTF-8' '-cp' $classpath 'game.server.GameServer' "$Port"
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
    exit $exitCode
} catch {
    Write-Host "Server failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
