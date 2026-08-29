#!/usr/bin/env bash
set -euo pipefail

# Non-destructive logical backup helper. The target directory is explicit so
# an operator cannot accidentally overwrite an arbitrary workspace path.
if [[ $# -ne 1 || -z "${1}" ]]; then
  echo "usage: $0 <backup-directory>" >&2
  exit 2
fi

BACKUP_DIR="$1"
case "${BACKUP_DIR}" in
  /|.|..|./|../) echo "refusing an unsafe backup directory" >&2; exit 2 ;;
esac
mkdir -p -- "${BACKUP_DIR}"

: "${PGHOST:?set PGHOST}"
: "${PGPORT:=5432}"
: "${PGUSER:?set PGUSER}"
: "${PGDATABASE:?set PGDATABASE}"
: "${PGDUMP_FILE:=${BACKUP_DIR}/socp-${PGDATABASE}-$(date -u +%Y%m%dT%H%M%SZ).dump}"

umask 077
pg_dump --format=custom --no-owner --no-privileges \
  --file="${PGDUMP_FILE}" \
  "${PGDATABASE}"
sha256sum "${PGDUMP_FILE}" > "${PGDUMP_FILE}.sha256"
printf 'backup=%s\nchecksum=%s\n' "${PGDUMP_FILE}" "${PGDUMP_FILE}.sha256"
