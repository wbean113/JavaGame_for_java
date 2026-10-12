# Shared helpers. This file only changes environment variables inside the running process.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:GameProjectRoot = $PSScriptRoot

function Find-GameJdk {
    $candidates = [System.Collections.Generic.List[string]]::new()
    foreach ($candidate in @($env:JAVA21_HOME, $env:JAVA_HOME)) {
        if ($candidate) { $candidates.Add($candidate) }
    }
    foreach ($parent in @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Microsoft', 'C:\Program Files\Amazon Corretto')) {
        if (Test-Path -LiteralPath $parent) {
            Get-ChildItem -LiteralPath $parent -Directory | Sort-Object Name -Descending | ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javac) { $candidates.Add((Split-Path (Split-Path $javac.Source -Parent) -Parent)) }
    foreach ($candidate in $candidates) {
        $release = Join-Path $candidate 'release'
        if ((Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) -and
            (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) -and
            (Test-Path -LiteralPath $release) -and
            ((Get-Content -LiteralPath $release -Raw) -match '(?m)^JAVA_VERSION="21(?:[.\-+]|"|$)')) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'JDK 21 was not found. Install JDK 21 or set JAVA21_HOME for this terminal, then run again. See README.md.'
}

function Find-GameMaven {
    $command = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $ideaRoot = 'C:\Program Files\JetBrains'
    if (Test-Path -LiteralPath $ideaRoot) {
        foreach ($directory in (Get-ChildItem -LiteralPath $ideaRoot -Directory | Sort-Object Name -Descending)) {
            $bundled = Join-Path $directory.FullName 'plugins\maven-plugin\lib\maven3\bin\mvn.cmd'
            if (Test-Path -LiteralPath $bundled) { return $bundled }
        }
    }

    # Pin the fallback build tool and check the official archive's SHA-512 before extraction.
    $version = '3.9.9'
    $toolsDirectory = Join-Path $script:GameProjectRoot '.tools'
    $maven = Join-Path $toolsDirectory "apache-maven-$version\bin\mvn.cmd"
    if (Test-Path -LiteralPath $maven) { return $maven }
    New-Item -ItemType Directory -Path $toolsDirectory -Force | Out-Null
    $archive = Join-Path $toolsDirectory "apache-maven-$version-bin.zip"
    $checksumFile = "$archive.sha512"
    $url = "https://archive.apache.org/dist/maven/maven-3/$version/binaries/apache-maven-$version-bin.zip"
    $oldSecurityProtocol = [Net.ServicePointManager]::SecurityProtocol
    try {
        [Net.ServicePointManager]::SecurityProtocol = $oldSecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Write-Host "Downloading Apache Maven $version to .tools (first run only)..."
        Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing
        Invoke-WebRequest -Uri "$url.sha512" -OutFile $checksumFile -UseBasicParsing
        $checksumText = Get-Content -LiteralPath $checksumFile -Raw
        $checksumMatch = [regex]::Match($checksumText, '(?i)\b[0-9a-f]{128}\b')
        if (-not $checksumMatch.Success) { throw 'The Maven SHA-512 response was invalid.' }
        $actualChecksum = (Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash
        if ($actualChecksum -ne $checksumMatch.Value) { throw 'The downloaded Maven archive failed its SHA-512 check.' }
        Expand-Archive -LiteralPath $archive -DestinationPath $toolsDirectory -Force
    } finally {
        [Net.ServicePointManager]::SecurityProtocol = $oldSecurityProtocol
    }
    if (-not (Test-Path -LiteralPath $maven)) { throw 'Maven extraction did not produce bin\mvn.cmd.' }
    return $maven
}

function Get-GameClasspath {
    $jar = Join-Path $script:GameProjectRoot 'target\psychological-balance-1.0.0.jar'
    $libraries = Join-Path $script:GameProjectRoot 'target\lib'
    if (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath $libraries)) {
        throw 'Build output is missing. Run build.cmd first, then launch the client or server.'
    }
    return "$jar;$libraries\*"
}
