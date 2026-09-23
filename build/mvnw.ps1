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
# Windows PowerShell 5.1 promotes redirected native stderr to error records.
# JVM warnings must remain visible without aborting a successful Maven run;
# only the native exit code determines success. Restore the caller preference.
$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    & $maven.Source @arguments
    $mavenExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($mavenExitCode -ne 0) {
    throw "Maven exited with code $mavenExitCode"
}
