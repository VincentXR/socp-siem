#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Make the opt-in middleware suite explicit.  When Docker is available a
# normal local quality run should exercise the Testcontainers contracts just
# like CI; when it is unavailable, leave an explicit diagnostic instead of a
# silent green skip.  An explicitly supplied value always wins.
if [[ -z "${SOCP_TESTCONTAINERS:-}" ]]; then
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    export SOCP_TESTCONTAINERS=true
    echo "[quality-gate] Docker detected; enabling Testcontainers contracts"
  else
    export SOCP_TESTCONTAINERS=false
    echo "[quality-gate] Docker unavailable; Testcontainers contracts are skipped" >&2
  fi
fi

bash build/mvnw.sh test -Pcoverage -Dsurefire.failIfNoSpecifiedTests=false
python3 build/verify-coverage.py
python3 build/verify-changed-coverage.py
python3 build/verify-migrations.py
python3 build/verify-contracts.py
python3 build/verify-middleware-images.py
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
