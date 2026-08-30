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
python3 build/verify-frontend-i18n.py
python3 build/verify-event-schema.py
python3 build/verify-production.py
python3 build/validate-detection-content.py
python3 build/generate-detection-summary.py --check-readme
python3 build/verify-investigation-dataset.py
python3 build/eval-investigation.py --results services/ai-assistant/target/investigation-eval-results.json
bash build/mvnw.sh verify -Pquality -DskipTests

cd frontend
# Reuse the repository toolchain resolver so local Git Bash works with a
# globally installed pnpm, Corepack, or npx fallback. GitHub Actions installs
# pnpm explicitly, so this resolves to the same binary there.
source "$ROOT/build/toolchain.sh"
PNPM_COMMAND="$(socp_pnpm)"
read -r -a PNPM_ARGS <<< "$PNPM_COMMAND"
"${PNPM_ARGS[@]}" install --frozen-lockfile
"${PNPM_ARGS[@]}" --dir apps/workbench test
"${PNPM_ARGS[@]}" --dir apps/workbench lint
"${PNPM_ARGS[@]}" --dir apps/workbench format:check
"${PNPM_ARGS[@]}" --dir apps/workbench verify
