#!/usr/bin/env bash
set -euo pipefail

# Back up every registered SOCP database into ONE directory and emit a single
# manifest, so "one consistency backup" is a declarable object (docs/operations/
# backup-restore.md). The per-database dump and SHA-256 sidecar are produced by
# build/backup-postgres.sh; this script only adds the roster walk and the
# manifest that makes completeness checkable.
#
# Usage: backup-postgres-all.sh <backup-directory>
# Requires: PGHOST, PGUSER (PGPORT optional, defaults 5432). Each target database
# is named explicitly so a single PGDATABASE cannot silently under-scope a "full"
# backup — the original failure mode where only one service DB was ever dumped.
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

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
roster="${script_dir}/postgres-databases.txt"
if [[ ! -f "${roster}" ]]; then
  echo "database roster not found: ${roster}" >&2
  exit 2
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
manifest="${BACKUP_DIR}/manifest-${stamp}.txt"

# Read the server version once for the manifest header.
server_version="$(psql --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" \
  --dbname=postgres --tuples-only --no-align \
  --command='show server_version' || true)"

{
  printf '# SOCP postgres backup manifest\n'
  printf '# generated=%s\n' "${stamp}"
  printf '# host=%s port=%s\n' "${PGHOST}" "${PGPORT}"
  printf '# server_version=%s\n' "${server_version}"
  printf '# columns=database\tsha256\tdump\n'
} > "${manifest}"

count=0
while IFS= read -r line || [[ -n "${line}" ]]; do
  # strip comments and blank lines from the roster
  db="${line%%#*}"
  db="$(echo "${db}" | tr -d '[:space:]')"
  [[ -z "${db}" ]] && continue
  dump="${BACKUP_DIR}/socp-${db}-${stamp}.dump"
  echo "==> backing up ${db}" >&2
  PGDATABASE="${db}" PGDUMP_FILE="${dump}" "${script_dir}/backup-postgres.sh" "${BACKUP_DIR}"
  # backup-postgres.sh prints backup=... checksum=...; recompute a stable sha here.
  sha="$(cut -d' ' -f1 <<<"$(sha256sum "${dump}")")"
  printf '%s\t%s\t%s\n' "${db}" "${sha}" "${dump}" >> "${manifest}"
  count=$((count + 1))
done < "${roster}"

printf 'manifest=%s\ndatabases=%s\n' "${manifest}" "${count}"
if [[ "${count}" -eq 0 ]]; then
  echo "roster produced zero dumps" >&2
  exit 1
fi
