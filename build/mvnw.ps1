<#
.SYNOPSIS
  Windows-native Maven entry point matching build/mvnw.sh.
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArguments
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$settings = if ($env:SOCP_MAVEN_SETTINGS) { $env:SOCP_MAVEN_SETTINGS } else {
    Join-Path $repositoryRoot 'build/settings-mirror.xml'
}
$maven = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $maven) {
    throw 'mvn was not found on PATH. Install Maven 3.9+ or invoke this project from Git Bash with build/mvnw.sh.'
}

$arguments = @('-B')
if ($settings -and $settings -ne 'none' -and (Test-Path -LiteralPath $settings)) {
    $arguments += @('-s', $settings)
}
$arguments += @('-f', (Join-Path $repositoryRoot 'pom.xml'))
$arguments += $MavenArguments
& $maven.Source @arguments
exit $LASTEXITCODE
