#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bash build/mvnw.sh test -Pcoverage -Dsurefire.failIfNoSpecifiedTests=false
python3 build/verify-coverage.py
python3 build/verify-changed-coverage.py
python3 build/verify-migrations.py
python3 build/verify-contracts.py
python3 build/verify-package-layout.py
python3 build/verify-architecture.py
python3 build/verify-style.py
python3 build/verify-production.py
python3 build/validate-detection-content.py
python3 build/verify-investigation-dataset.py
python3 build/eval-investigation.py --results services/ai-assistant/target/investigation-eval-results.json
bash build/mvnw.sh verify -Pquality -DskipTests

cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm --dir apps/workbench test
corepack pnpm --dir apps/workbench verify
