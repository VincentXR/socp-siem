#!/usr/bin/env bash
set -euo pipefail

# Restore helper, the missing half of build/backup-postgres.sh.
#
# A backup with no restore path is not a recovery capability: nothing proves the
# dump is readable until it has been restored somewhere. This script restores
# into an explicitly named database so an operator cannot point it at a
# database they did not name, and refuses the databases that must never be a
# restore target.
#
# Usage: restore-postgres.sh <dump-file> <target-database>
if [[ $# -ne 2 || -z "${1}" || -z "${2}" ]]; then
  echo "usage: $0 <dump-file> <target-database>" >&2
  exit 2
fi

DUMP_FILE="$1"
TARGET_DB="$2"

: "${PGHOST:?set PGHOST}"
: "${PGPORT:=5432}"
: "${PGUSER:?set PGUSER}"

# Databases that must never be overwritten by a restore. PGDATABASE is included
# because restoring a dump over its own source is a destructive no-op at best.
PROTECTED="postgres template0 template1 ${PGDATABASE:-}"
for name in ${PROTECTED}; do
  if [[ "${TARGET_DB}" == "${name}" ]]; then
    echo "refusing to restore into protected database: ${TARGET_DB}" >&2
    exit 2
  fi
done

if [[ ! -f "${DUMP_FILE}" ]]; then
  echo "dump file not found: ${DUMP_FILE}" >&2
  exit 2
fi

# Verify the sidecar written by backup-postgres.sh when it is present. A dump
# that is truncated or tampered with must fail here, not halfway through.
if [[ -f "${DUMP_FILE}.sha256" ]]; then
  sha256sum --check --status "${DUMP_FILE}.sha256" \
    || { echo "checksum mismatch for ${DUMP_FILE}" >&2; exit 1; }
  echo "checksum=verified"
else
  echo "checksum=missing sidecar; restored without verification"
fi

# Create rather than reuse: pg_restore into an existing database appends to
# whatever is already there, and a partial restore would look like success.
if psql --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" \
      --dbname=postgres --tuples-only --no-align \
      --command="select 1 from pg_database where datname='${TARGET_DB}'" | grep -q 1; then
  echo "target database already exists: ${TARGET_DB}" >&2
  echo "drop it explicitly or choose another name" >&2
  exit 2
fi

createdb --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" "${TARGET_DB}"

umask 077
pg_restore \
  --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" \
  --dbname="${TARGET_DB}" \
  --no-owner --no-privileges \
  "${DUMP_FILE}"

printf 'restored=%s\ninto=%s\n' "${DUMP_FILE}" "${TARGET_DB}"
