$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # Keep local behaviour aligned with CI: automatically enable the
    # Testcontainers contracts when Docker is usable, otherwise make the
    # environment-dependent skip explicit. An explicit caller value wins.
    if ([string]::IsNullOrWhiteSpace($env:SOCP_TESTCONTAINERS)) {
        $docker = Get-Command docker -ErrorAction SilentlyContinue
        $dockerReady = $false
        if ($null -ne $docker) {
            & $docker.Source info *> $null
            $dockerReady = ($LASTEXITCODE -eq 0)
        }
        if ($dockerReady) {
            $env:SOCP_TESTCONTAINERS = 'true'
            Write-Host '[quality-gate] Docker detected; enabling Testcontainers contracts'
        } else {
            $env:SOCP_TESTCONTAINERS = 'false'
            Write-Warning '[quality-gate] Docker unavailable; Testcontainers contracts are skipped'
        }
    }
    & (Join-Path $root 'build/mvnw.ps1') verify '-Pcoverage,quality' '-Dsurefire.failIfNoSpecifiedTests=false'
    if ($LASTEXITCODE -ne 0) { throw 'Maven tests or static analysis failed' }
    python build/verify-coverage.py
    if ($LASTEXITCODE -ne 0) { throw 'Coverage gate failed' }
    python build/verify-changed-coverage.py
    if ($LASTEXITCODE -ne 0) { throw 'Changed-line coverage gate failed' }
    python build/verify-repository.py
    if ($LASTEXITCODE -ne 0) { throw 'Repository contract gate failed' }

    Push-Location frontend
    try {
        $packageManager = (Get-Content -Raw package.json | ConvertFrom-Json).packageManager
        $expectedPnpmVersion = $packageManager -replace '^pnpm@', ''
        $pnpmExecutable = $null
        $pnpmPrefix = @()
        $pnpm = Get-Command pnpm -ErrorAction SilentlyContinue
        if ($null -ne $pnpm) {
            $actualPnpmVersion = (& $pnpm.Source --version).Trim()
            if ($actualPnpmVersion -eq $expectedPnpmVersion) {
                $pnpmExecutable = $pnpm.Source
            }
        }
        if ($null -eq $pnpmExecutable) {
            $corepack = Get-Command corepack -ErrorAction SilentlyContinue
            if ($null -ne $corepack) {
                $pnpmExecutable = $corepack.Source
                $pnpmPrefix = @($packageManager)
            } else {
                $npx = Get-Command npx -ErrorAction SilentlyContinue
                if ($null -eq $npx) {
                    throw "pnpm $expectedPnpmVersion is required, but no matching pnpm, corepack, or npx was found"
                }
                $pnpmExecutable = $npx.Source
                $pnpmPrefix = @('--yes', $packageManager)
            }
        }

        & $pnpmExecutable @pnpmPrefix install --frozen-lockfile
        if ($LASTEXITCODE -ne 0) { throw 'Frontend install failed' }
        & $pnpmExecutable @pnpmPrefix --dir apps/workbench test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed' }
        & $pnpmExecutable @pnpmPrefix --dir apps/workbench lint
        if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed' }
        & $pnpmExecutable @pnpmPrefix --dir apps/workbench format:check
        if ($LASTEXITCODE -ne 0) { throw 'Frontend format check failed' }
        & $pnpmExecutable @pnpmPrefix --dir apps/workbench verify
        if ($LASTEXITCODE -ne 0) { throw 'Frontend verification failed' }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
