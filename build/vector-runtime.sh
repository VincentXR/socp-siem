#!/usr/bin/env bash
# Build a private, hash-pinned Vector runtime. No registry publication.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$ROOT/agents/vector-runtime/build.py" "$@"
