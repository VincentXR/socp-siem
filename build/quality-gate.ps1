$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    mvn -s build/settings-mirror.xml -f pom.xml test -Pcoverage '-Dsurefire.failIfNoSpecifiedTests=false'
    if ($LASTEXITCODE -ne 0) { throw 'Maven coverage tests failed' }
    python build/verify-coverage.py
    if ($LASTEXITCODE -ne 0) { throw 'Coverage gate failed' }
    python build/verify-changed-coverage.py
    if ($LASTEXITCODE -ne 0) { throw 'Changed-line coverage gate failed' }
    python build/verify-migrations.py
    if ($LASTEXITCODE -ne 0) { throw 'Migration gate failed' }
    python build/verify-contracts.py
    if ($LASTEXITCODE -ne 0) { throw 'Contract gate failed' }
    python build/verify-package-layout.py
    if ($LASTEXITCODE -ne 0) { throw 'Package layout gate failed' }
    python build/verify-architecture.py
    if ($LASTEXITCODE -ne 0) { throw 'Architecture gate failed' }
    python build/verify-style.py
    if ($LASTEXITCODE -ne 0) { throw 'Style debt gate failed' }
    python build/verify-production.py
    if ($LASTEXITCODE -ne 0) { throw 'Production deployment contract gate failed' }
    python build/validate-detection-content.py
    if ($LASTEXITCODE -ne 0) { throw 'Detection content gate failed' }
    python build/verify-investigation-dataset.py
    if ($LASTEXITCODE -ne 0) { throw 'Investigation dataset gate failed' }
    python build/eval-investigation.py --results services/ai-assistant/target/investigation-eval-results.json
    if ($LASTEXITCODE -ne 0) { throw 'Investigation evaluation gate failed' }
    mvn -s build/settings-mirror.xml -f pom.xml verify -Pquality -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Static analysis gate failed' }

    Push-Location frontend
    try {
        pnpm install --frozen-lockfile
        if ($LASTEXITCODE -ne 0) { throw 'Frontend install failed' }
        pnpm --dir apps/workbench test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed' }
        pnpm --dir apps/workbench verify
        if ($LASTEXITCODE -ne 0) { throw 'Frontend verification failed' }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
