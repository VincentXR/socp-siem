#!/usr/bin/env python3
"""Maintenance-window logical backup with database-enforced fences across the roster.

All application tables stay SHARE-locked until every dump finishes. Exported
snapshots are taken only after every fence is held. A lost fence, failed dump,
observed table-roster change or checksum failure prevents manifest publication.
Maintenance quiescence is required for DDL and non-table state such as sequences.
Requires only Python 3 and PostgreSQL's psql/pg_dump (no Python DB driver).
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import os
from pathlib import Path
import re
import select
import signal
import subprocess
import sys
import time
import uuid

BUILD = Path(__file__).resolve().parent
TABLES = """SELECT format('%I.%I', n.nspname, c.relname) AS name
 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind IN ('r', 'p') AND n.nspname NOT LIKE 'pg_%'
 AND n.nspname <> 'information_schema'"""
SCHEMA = "SELECT coalesce(md5(string_agg(name, ',' ORDER BY name)), 'empty') FROM (" + TABLES + ") tables"
FENCE_SQL = """BEGIN;
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '60s';
DO $fence$ DECLARE relations text; BEGIN
 SELECT string_agg(name, ', ' ORDER BY name) INTO relations FROM (
""" + TABLES + """
 ) tables;
 IF relations IS NOT NULL THEN EXECUTE 'LOCK TABLE ' || relations || ' IN SHARE MODE'; END IF;
END $fence$;
"""


def roster(path: Path) -> list[str]:
    names = [line.split('#', 1)[0].strip() for line in path.read_text().splitlines()]
    names = [name for name in names if name]
    if not names or len(names) != len(set(names)) or any(not re.fullmatch(r'[a-z][a-z0-9_]*', name) for name in names):
        raise ValueError('database roster must contain unique, simple database names')
    return names


class Fence:
    def __init__(self, database: str):
        self.database = database
        self.buffer = b''
        self.process = subprocess.Popen(
            ['psql', '--no-psqlrc', '--no-password', '--quiet', '--tuples-only', '--no-align',
             '--set=ON_ERROR_STOP=1', '--host=' + os.environ['PGHOST'],
             '--port=' + os.environ.get('PGPORT', '5432'), '--username=' + os.environ['PGUSER'],
             '--dbname=' + database], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            env={**os.environ, 'PGAPPNAME': 'socp-backup-fence'})
        try:
            self.query(FENCE_SQL)
            self.schema = self.query(SCHEMA)[0]
        except BaseException:
            self.close()
            raise

    def alive(self) -> bool:
        return self.process.poll() is None

    def query(self, sql: str, timeout: float = 65) -> list[str]:
        marker = 'socp_backup_' + uuid.uuid4().hex
        if not self.alive():
            raise RuntimeError('backup fence connection lost: ' + self.database)
        assert self.process.stdin is not None and self.process.stdout is not None
        self.process.stdin.write((sql.rstrip(';\n ') + ";\nSELECT '" + marker + "';\n").encode())
        self.process.stdin.flush()
        deadline = time.monotonic() + timeout
        rows: list[str] = []
        while time.monotonic() < deadline:
            while b'\n' in self.buffer:
                line, self.buffer = self.buffer.split(b'\n', 1)
                text = line.decode('utf-8', errors='replace').strip()
                if text == marker:
                    return rows
                if text:
                    rows.append(text)
            if not self.alive():
                raise RuntimeError('backup fence SQL failed: ' + self.database + ': ' + ' '.join(rows)[-1000:])
            ready, _, _ = select.select([self.process.stdout], [], [], min(0.2, max(0, deadline - time.monotonic())))
            if ready:
                chunk = os.read(self.process.stdout.fileno(), 8192)
                if not chunk:
                    raise RuntimeError('backup fence connection closed: ' + self.database)
                self.buffer += chunk
        raise RuntimeError('backup fence timed out: ' + self.database)

    def snapshot(self) -> str:
        result = self.query('SELECT pg_export_snapshot()')
        if len(result) != 1 or not re.fullmatch(r'[0-9A-Fa-f-]+', result[0]):
            raise RuntimeError('invalid exported snapshot: ' + self.database)
        return result[0]

    def verify(self) -> None:
        if self.query(SCHEMA) != [self.schema]:
            raise RuntimeError('schema changed while the backup was fenced: ' + self.database)

    def close(self) -> None:
        # Closing stdin releases the transaction even after an interrupted backup.
        if self.process.stdin is not None:
            try:
                self.process.stdin.close()
            except OSError:
                pass
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        if self.process.stdout is not None:
            self.process.stdout.close()


def assert_fences(fences: list[Fence]) -> None:
    if any(not fence.alive() for fence in fences):
        raise RuntimeError('a database write fence was lost; backup set is incomplete')


def dump(database: str, snapshot: str, destination: Path, fences: list[Fence]) -> Path:
    target = destination / (database + '.dump')
    env = {**os.environ, 'PGDATABASE': database, 'PGDUMP_FILE': str(target), 'PGSNAPSHOT': snapshot}
    with (destination / (database + '.log')).open('wb') as log:
        process = subprocess.Popen(['bash', str(BUILD / 'backup-postgres.sh'), str(destination)],
                                   env=env, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=os.name == 'posix')
        try:
            deadline = time.monotonic() + 3600
            while process.poll() is None:
                assert_fences(fences)
                if time.monotonic() >= deadline:
                    raise RuntimeError('database dump exceeded one hour: ' + database)
                time.sleep(0.2)
            if process.returncode:
                raise RuntimeError('database dump failed; inspect ' + str(log.name))
        finally:
            if process.poll() is None:
                if os.name == 'posix':
                    os.killpg(process.pid, signal.SIGTERM)
                else:
                    process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    if os.name == 'posix':
                        os.killpg(process.pid, signal.SIGKILL)
                    else:
                        process.kill()
                    process.wait()
    return target


def backup(destination: Path, names: list[str], fence_type=Fence, dump_action=dump) -> Path:
    destination = destination.resolve()
    if destination.exists() and any(destination.iterdir()):
        raise ValueError('backup directory must be empty; existing data will not be overwritten')
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(destination, 0o700)
    fences: list[Fence] = []
    manifest = destination / 'manifest.txt'
    pending = destination / 'manifest.incomplete'
    pending.write_text('# INCOMPLETE: no recovery point until manifest.txt is published\n')
    try:
        for database in names:
            fences.append(fence_type(database))
        assert_fences(fences)
        snapshots = {fence.database: fence.snapshot() for fence in fences}
        lines = ['# SOCP PostgreSQL fenced backup v2', '# consistency=all-database-table-fences',
                 '# fenced_at=' + datetime.now(timezone.utc).isoformat(), '# columns=database\tsha256\tdump']
        for database in names:
            assert_fences(fences)
            target = dump_action(database, snapshots[database], destination, fences)
            with target.open('rb') as stream:
                digest = hashlib.sha256()
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            checksum = digest.hexdigest()
            sidecar = Path(str(target) + '.sha256').read_text().split()[0]
            if checksum != sidecar:
                raise RuntimeError('dump checksum mismatch: ' + database)
            lines.append(database + '\t' + checksum + '\t' + str(target))
        for fence in fences:
            fence.verify()
        assert_fences(fences)
        pending.write_text('\n'.join(lines) + '\n')
        pending.replace(manifest)
        return manifest
    finally:
        for fence in reversed(fences):
            fence.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maintenance', action='store_true', required=True,
                        help='acknowledge that ingestion, applications, workers and schema migrations are stopped')
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    for name in ('PGHOST', 'PGUSER'):
        if not os.environ.get(name):
            parser.error('set ' + name + ' explicitly')
    os.umask(0o077)
    try:
        manifest = backup(args.directory, roster(BUILD / 'postgres-databases.txt'))
        print('manifest=' + str(manifest))
        return 0
    except (OSError, ValueError, RuntimeError) as failure:
        print('backup failed: ' + str(failure), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
