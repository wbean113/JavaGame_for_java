[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
try {
    . (Join-Path $PSScriptRoot 'project-env.ps1')
    $jdk = Find-GameJdk
    $classpath = Get-GameClasspath
    Push-Location -LiteralPath $PSScriptRoot
    try {
        & (Join-Path $jdk 'bin\java.exe') '-Dfile.encoding=UTF-8' '-cp' $classpath 'game.client.Launcher'
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
    exit $exitCode
} catch {
    Write-Host "Client failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
