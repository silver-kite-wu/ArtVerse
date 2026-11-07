param(
    [switch]$Background,
    [ValidatePattern('^[a-zA-Z0-9.:\-]+$')]
    [string]$RedisHost
)

$ErrorActionPreference = 'Stop'

if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8080 is already in use. Stop the existing backend before starting a new local backend.'
}

# Run from this script's directory so Spring can resolve ../.env consistently.
$backendDirectory = $PSScriptRoot
$localOverrides = @{
    ARTVERSE_DEV_MODE = 'true'
    SPRING_PROFILES_ACTIVE = 'local'
}
if ($RedisHost) {
    $localOverrides['SPRING_DATA_REDIS_HOST'] = $RedisHost
}

if ($Background) {
    $logDirectory = Join-Path $backendDirectory 'target'
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $shellPath = (Get-Process -Id $PID).Path
    $launchArguments = @('-NoProfile', '-File', ('"{0}"' -f $PSCommandPath))
    if ($RedisHost) {
        $launchArguments += @('-RedisHost', $RedisHost)
    }
    $process = Start-Process -FilePath $shellPath `
        -ArgumentList $launchArguments `
        -WorkingDirectory $backendDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDirectory 'backend-local.log') `
        -RedirectStandardError (Join-Path $logDirectory 'backend-local.err.log')
    Write-Host "Local backend launcher PID: $($process.Id)"
    Write-Host "Logs: $logDirectory/backend-local.log and backend-local.err.log"
    return
}

$previousEnvironment = @{}
foreach ($name in $localOverrides.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

Push-Location $backendDirectory
try {
    foreach ($name in $localOverrides.Keys) {
        [Environment]::SetEnvironmentVariable($name, $localOverrides[$name], 'Process')
    }
    Write-Host 'Starting local HTTP backend on port 8080 (ARTVERSE_DEV_MODE=true: localhost origins, insecure cookies, CSRF enforced).'
    & mvn '-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8' `
        '-Dspring-boot.run.arguments=--artverse.auth.csrf.mode=enforce' spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Local backend exited with code $LASTEXITCODE."
    }
} finally {
    foreach ($name in $localOverrides.Keys) {
        if ($null -eq $previousEnvironment[$name]) {
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
    }
    Pop-Location
}
