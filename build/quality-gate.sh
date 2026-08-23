#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bash build/mvnw.sh test -Pcoverage -Dsurefire.failIfNoSpecifiedTests=false
python3 build/verify-coverage.py
python3 build/verify-migrations.py
python3 build/verify-contracts.py
python3 build/validate-detection-content.py
bash build/mvnw.sh verify -Pquality -DskipTests

cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm --dir apps/workbench test
corepack pnpm --dir apps/workbench verify
