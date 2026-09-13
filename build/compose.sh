#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CATALOG="$ROOT/infra/middleware-images.env"
COMPOSE="$ROOT/infra/docker-compose.yml"

if [ ! -f "$CATALOG" ]; then
  echo "missing middleware image catalog: $CATALOG" >&2
  exit 1
fi

# Docker Compose gives inherited environment variables precedence over
# --env-file. Remove catalog keys from this process so a stale shell export
# cannot silently select a different middleware image than the committed
# catalog. Use a different catalog file explicitly when an isolated stack is
# intentional.
while IFS='=' read -r key _; do
  case "$key" in
    SOCP_*_IMAGE) unset "$key" ;;
  esac
done < "$CATALOG"

exec docker compose --env-file "$CATALOG" -f "$COMPOSE" "$@"
