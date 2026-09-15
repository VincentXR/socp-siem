#!/usr/bin/env bash
set -euo pipefail

# Backup/restore drill for PostgreSQL.
#
# A restore that has never been exercised is an assumption, not a capability.
# This drill closes the loop against a live server: dump the database, restore
# it under a different name, compare the tables and row counts, and report how
# long each phase took so an RTO estimate has a measured basis.
#
# Usage: drill-postgres-restore.sh <work-directory> [--keep]
#
#   --keep   leave the restored database in place for manual inspection
#
# Required environment: PGHOST, PGUSER, PGDATABASE (optional PGPORT)
if [[ $# -lt 1 || -z "${1}" ]]; then
  echo "usage: $0 <work-directory> [--keep]" >&2
  exit 2
fi

WORK_DIR="$1"
KEEP=false
if [[ "${2:-}" == "--keep" ]]; then
  KEEP=true
fi

: "${PGHOST:?set PGHOST}"
: "${PGPORT:=5432}"
: "${PGUSER:?set PGUSER}"
: "${PGDATABASE:?set PGDATABASE}"

TARGET_DB="${PGDATABASE}_restore_check"
# Sibling scripts are resolved relative to this one, not to the repository
# root, so the drill still works when the three scripts are copied next to each
# other onto a host that has the PostgreSQL client tools.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PSQL=(psql --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" --tuples-only --no-align)

mkdir -p -- "${WORK_DIR}"

# Drop a leftover database from a previous run so the drill is repeatable.
if "${PSQL[@]}" --dbname=postgres \
     --command="select 1 from pg_database where datname='${TARGET_DB}'" | grep -q 1; then
  dropdb --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" "${TARGET_DB}"
  echo "dropped leftover ${TARGET_DB}"
fi

# Exact row counts. pg_stat_user_tables.n_live_tup is an estimator and is not
# comparable across databases: a freshly restored database has its own stats, so
# two identical databases can honestly report different estimates.
tables_in() {
  "${PSQL[@]}" --dbname="$1" --command="
    select table_name from information_schema.tables
    where table_schema='public' and table_type='BASE TABLE'
    order by table_name;"
}

snapshot() {
  local database="$1"
  local table
  local count
  while IFS= read -r table; do
    [[ -z "${table}" ]] && continue
    count="$("${PSQL[@]}" --dbname="${database}" \
      --command="select count(*) from public.\"${table}\";")"
    printf '%s %s\n' "${table}" "${count}"
  done < <(tables_in "${database}")
}

echo "== source summary =="
SOURCE_TABLES="$(tables_in "${PGDATABASE}" | grep -c . || true)"
echo "tables=${SOURCE_TABLES}"
if [[ "${SOURCE_TABLES}" == "0" ]]; then
  echo "WARNING: ${PGDATABASE} has no tables; a row-count comparison would be"
  echo "vacuous. Run this against a populated database for a meaningful drill."
fi

echo
echo "== backup =="
BACKUP_START=$(date +%s)
bash "${SCRIPT_DIR}/backup-postgres.sh" "${WORK_DIR}" | tee "${WORK_DIR}/backup.log"
BACKUP_END=$(date +%s)

DUMP_FILE="$(grep -o 'backup=.*' "${WORK_DIR}/backup.log" | cut -d= -f2-)"
if [[ -z "${DUMP_FILE}" || ! -f "${DUMP_FILE}" ]]; then
  echo "backup did not produce a dump file" >&2
  exit 1
fi
echo "backup seconds=$((BACKUP_END - BACKUP_START))"

echo
echo "== restore =="
RESTORE_START=$(date +%s)
bash "${SCRIPT_DIR}/restore-postgres.sh" "${DUMP_FILE}" "${TARGET_DB}"
RESTORE_END=$(date +%s)
echo "restore seconds=$((RESTORE_END - RESTORE_START))"

echo
echo "== compare =="
SOURCE_SNAPSHOT="$(snapshot "${PGDATABASE}")"
TARGET_SNAPSHOT="$(snapshot "${TARGET_DB}")"

FAILED=false
if [[ "${SOURCE_SNAPSHOT}" != "${TARGET_SNAPSHOT}" ]]; then
  echo "row counts differ between ${PGDATABASE} and ${TARGET_DB}"
  diff <(echo "${SOURCE_SNAPSHOT}") <(echo "${TARGET_SNAPSHOT}") || true
  FAILED=true
else
  echo "row counts match for ${SOURCE_TABLES} tables"
fi

TARGET_TABLES="$(tables_in "${TARGET_DB}" | grep -c . || true)"
if [[ "${SOURCE_TABLES}" != "${TARGET_TABLES}" ]]; then
  echo "table count differs: source=${SOURCE_TABLES} restored=${TARGET_TABLES}"
  FAILED=true
fi

if [[ "${KEEP}" == "false" ]]; then
  dropdb --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" "${TARGET_DB}"
  echo "dropped ${TARGET_DB}"
else
  echo "kept ${TARGET_DB} for inspection"
fi

echo
if [[ "${FAILED}" == "true" ]]; then
  echo "DRILL FAILED"
  exit 1
fi
echo "DRILL PASSED (backup=$((BACKUP_END - BACKUP_START))s restore=$((RESTORE_END - RESTORE_START))s)"
