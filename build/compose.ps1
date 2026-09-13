[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $ComposeArguments
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$catalog = Join-Path $repositoryRoot 'infra/middleware-images.env'
$compose = Join-Path $repositoryRoot 'infra/docker-compose.yml'

if (-not (Test-Path -LiteralPath $catalog)) {
    throw "Missing middleware image catalog: $catalog"
}

# Docker Compose gives inherited environment variables precedence over
# --env-file. Remove catalog keys from this child process so a stale shell
# export cannot silently select a different middleware image than the
# committed catalog.
$catalogKeys = Get-Content -LiteralPath $catalog |
    Where-Object { $_ -match '^(SOCP_[A-Z0-9_]*_IMAGE)=' } |
    ForEach-Object { $Matches[1] }
$savedEnvironment = @{}
foreach ($key in $catalogKeys) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
}

try {
    & docker compose --env-file $catalog -f $compose @ComposeArguments
    $status = $LASTEXITCODE
}
finally {
    foreach ($key in $catalogKeys) {
        if ($null -eq $savedEnvironment[$key]) {
            Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
        }
        else {
            Set-Item -Path "Env:$key" -Value $savedEnvironment[$key]
        }
    }
}
exit $status
