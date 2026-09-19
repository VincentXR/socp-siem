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

bash build/mvnw.sh verify -Pcoverage,quality -Dsurefire.failIfNoSpecifiedTests=false
python3 build/verify-coverage.py
python3 build/verify-changed-coverage.py
python3 build/verify-repository.py

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
