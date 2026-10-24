"""Backup publication safety plus an optional disposable PostgreSQL fence/restore drill."""
from pathlib import Path
import hashlib
import importlib.util
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

BUILD = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('consistent_backup', BUILD / 'backup-postgres-consistent.py')
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)


class BackupPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.destination = Path(self.temporary.name) / 'backup'
        self.fences = []
        owner = self

        class FakeFence:
            def __init__(self, database):
                self.database = database
                self.held = True
                owner.fences.append(self)

            def alive(self):
                return self.held

            def snapshot(self):
                owner.assertEqual(len(owner.fences), 2, 'all databases must be fenced before any snapshot')
                return 'snapshot-' + self.database

            def verify(self):
                owner.assertTrue(self.held)

            def close(self):
                self.held = False

        self.fence_type = FakeFence

    @staticmethod
    def dump(database, snapshot, destination, fences):
        target = destination / (database + '.dump')
        target.write_bytes((database + snapshot).encode())
        Path(str(target) + '.sha256').write_text(hashlib.sha256(target.read_bytes()).hexdigest() + '  ' + str(target))
        return target

    def test_manifest_is_published_only_after_all_fenced_dumps_and_checksums(self):
        manifest = backup.backup(self.destination, ['alert', 'detect'], self.fence_type, self.dump)
        self.assertIn('consistency=all-database-table-fences', manifest.read_text())
        self.assertIn('alert\t', manifest.read_text())
        self.assertIn('detect\t', manifest.read_text())
        self.assertFalse((self.destination / 'manifest.incomplete').exists())
        self.assertTrue(all(not fence.held for fence in self.fences))

    def test_lost_fence_cannot_publish_a_successful_backup(self):
        def lose(database, snapshot, destination, fences):
            target = self.dump(database, snapshot, destination, fences)
            fences[0].held = False
            return target
        with self.assertRaisesRegex(RuntimeError, 'fence was lost'):
            backup.backup(self.destination, ['alert', 'detect'], self.fence_type, lose)
        self.assertFalse((self.destination / 'manifest.txt').exists())
        self.assertTrue((self.destination / 'manifest.incomplete').exists())
        self.assertTrue(all(not fence.held for fence in self.fences))

    def test_checksum_failure_preserves_incomplete_marker_and_releases_every_fence(self):
        def corrupt(database, snapshot, destination, fences):
            target = self.dump(database, snapshot, destination, fences)
            target.write_bytes(b'corrupt')
            return target
        with self.assertRaisesRegex(RuntimeError, 'checksum mismatch'):
            backup.backup(self.destination, ['alert', 'detect'], self.fence_type, corrupt)
        self.assertFalse((self.destination / 'manifest.txt').exists())
        self.assertTrue(all(not fence.held for fence in self.fences))

    def test_existing_backup_is_not_overwritten(self):
        self.destination.mkdir()
        protected = self.destination / 'existing.dump'
        protected.write_bytes(b'preserved')
        with self.assertRaisesRegex(ValueError, 'must be empty'):
            backup.backup(self.destination, ['alert', 'detect'], self.fence_type, self.dump)
        self.assertEqual(protected.read_bytes(), b'preserved')

    def test_restore_rejects_non_identifier_targets_before_opening_a_connection(self):
        for target in ("drill'; DROP TABLE guard;--", 'postgresql://localhost/other', '--maintenance-db=other'):
            with self.subTest(target=target):
                result = subprocess.run(['bash', str(BUILD / 'restore-postgres.sh'),
                    str(self.destination / 'missing.dump'), target], capture_output=True, text=True,
                    env={**os.environ, 'PGHOST': '127.0.0.1', 'PGPORT': '1', 'PGUSER': 'unused'})
                self.assertEqual(result.returncode, 2)
                self.assertIn('plain identifier', result.stderr)


@unittest.skipUnless(os.environ.get('SOCP_TEST_POSTGRES_BIN'), 'set SOCP_TEST_POSTGRES_BIN for a disposable native PostgreSQL drill')
class PostgresBackupDrill(unittest.TestCase):
    def test_late_writes_are_fenced_and_restored_databases_keep_the_same_cut(self):
        import socket
        import getpass
        with tempfile.TemporaryDirectory(prefix='socp-backup-drill-') as directory:
            root = Path(directory)
            bind = socket.socket()
            bind.bind(('127.0.0.1', 0))
            port = bind.getsockname()[1]
            bind.close()
            env = {key: value for key, value in os.environ.items() if not key.startswith('PG')}
            env.update(PATH=os.environ['SOCP_TEST_POSTGRES_BIN'] + os.pathsep + os.environ['PATH'],
                       PGHOST='127.0.0.1', PGPORT=str(port), PGUSER=getpass.getuser(),
                       PGPASSWORD='isolated-trust-drill-only')
            with patch.dict(os.environ, env, clear=True):
                def run(*args, **kwargs):
                    try:
                        return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs)
                    except subprocess.CalledProcessError as failure:
                        raise AssertionError(f"drill command failed: {args[0]}\n{failure.stdout}\n{failure.stderr}") from failure
                run('initdb', '-D', str(root / 'data'), '-A', 'trust', '--no-locale')
                run('pg_ctl', '-D', str(root / 'data'), '-l', str(root / 'postgres.log'),
                    '-o', '-h 127.0.0.1 -p ' + str(port) + ' -k ' + str(root), '-w', 'start')
                try:
                    for database in ('alert_fixture', 'detect_fixture'):
                        run('createdb', database)
                        run('psql', '-d', database, '-v', 'ON_ERROR_STOP=1', '-c',
                            "CREATE TABLE facts (id text primary key); INSERT INTO facts VALUES ('before');")
                    # The standalone helper must also work without a snapshot on Bash 3.2.
                    standalone = root / 'single.dump'
                    run('bash', str(BUILD / 'backup-postgres.sh'), str(root / 'single'),
                        env={**os.environ, 'PGDATABASE': 'alert_fixture', 'PGDUMP_FILE': str(standalone)})
                    self.assertTrue(standalone.is_file())
                    def checked_dump(database, snapshot, destination, fences):
                        for locked in ('alert_fixture', 'detect_fixture'):
                            attempted = subprocess.run(['psql', '-d', locked, '-v', 'ON_ERROR_STOP=1', '-c',
                                "SET lock_timeout='100ms'; INSERT INTO facts VALUES ('late');"],
                                capture_output=True, text=True)
                            self.assertNotEqual(attempted.returncode, 0)
                            self.assertIn('lock timeout', attempted.stderr)
                        return backup.dump(database, snapshot, destination, fences)
                    backup.backup(root / 'backup', ['alert_fixture', 'detect_fixture'], dump_action=checked_dump)
                    for database in ('alert_fixture', 'detect_fixture'):
                        run('bash', str(BUILD / 'restore-postgres.sh'),
                            str(root / 'backup' / (database + '.dump')), database + '_restore')
                        count = run('psql', '-At', '-d', database + '_restore', '-c', 'SELECT id FROM facts').stdout.strip()
                        self.assertEqual(count, 'before')
                        run('psql', '-d', database, '-c', "INSERT INTO facts VALUES ('released');")
                    # A finalized recovery must be usable by the restricted
                    # migration/runtime roles, not just readable by its admin.
                    role_environment = {**os.environ, 'POSTGRES_USER': getpass.getuser(), 'POSTGRES_DB': 'postgres',
                        'SOCP_PG_RUNTIME_USER': 'drill_runtime', 'SOCP_PG_RUNTIME_PASSWORD': 'isolated-runtime-only',
                        'SOCP_PG_MIGRATION_USER': 'drill_migrator', 'SOCP_PG_MIGRATION_PASSWORD': 'isolated-migrator-only'}
                    for _ in range(2):
                        run('bash', str(BUILD.parent / 'infra/init-sql/pg/00_roles.sh'), env=role_environment)
                    run('psql', '-d', 'alert_fixture', '-v', 'ON_ERROR_STOP=1', '-c',
                        "CREATE TABLE tenant_facts (tenant_id text, id text); "
                        "INSERT INTO tenant_facts VALUES ('a','owned'), ('b','other');")
                    finalized_dump = root / 'finalized.dump'
                    run('bash', str(BUILD / 'backup-postgres.sh'), str(root / 'finalized'),
                        env={**os.environ, 'PGDATABASE': 'alert_fixture', 'PGDUMP_FILE': str(finalized_dump)})
                    run('bash', str(BUILD / 'restore-postgres.sh'), '--finalize',
                        str(finalized_dump), 'finalized_restore', env={**os.environ,
                        'SOCP_PG_RUNTIME_USER': 'drill_runtime', 'SOCP_PG_MIGRATION_USER': 'drill_migrator'})
                    owner = run('psql', '-At', '-d', 'finalized_restore', '-c',
                        "SELECT tableowner FROM pg_tables WHERE tablename = 'tenant_facts'").stdout.strip()
                    self.assertEqual(owner, 'drill_migrator', 'future migrations must own restored tables')
                    visible = run('psql', '-At', '-d', 'finalized_restore', '-U', 'drill_runtime', '-c',
                        "SET socp.tenant_id='a'; SELECT id FROM tenant_facts ORDER BY id").stdout.strip()
                    self.assertEqual(visible.splitlines()[-1], 'owned')
                    run('psql', '-d', 'finalized_restore', '-U', 'drill_migrator', '-v', 'ON_ERROR_STOP=1',
                        '-c', 'ALTER TABLE tenant_facts ADD COLUMN after_recovery boolean')
                    run('psql', '-d', 'finalized_restore', '-U', 'drill_migrator', '-v', 'ON_ERROR_STOP=1',
                        '-c', 'UPDATE tenant_facts SET after_recovery=true')
                    migrated = run('psql', '-At', '-d', 'finalized_restore', '-U', 'drill_migrator', '-c',
                        'SELECT count(*) FROM tenant_facts WHERE after_recovery=true').stdout.strip()
                    self.assertEqual(migrated, '2', 'a restricted migration login must backfill all tenants under FORCE RLS')
                    unscoped_runtime = run('psql', '-At', '-d', 'finalized_restore', '-U', 'drill_runtime', '-c',
                        'SELECT count(*) FROM tenant_facts').stdout.strip()
                    self.assertEqual(unscoped_runtime, '0', 'runtime login must not inherit system tenant scope')
                    denied = subprocess.run(['psql', '-d', 'finalized_restore', '-U', 'drill_runtime',
                        '-v', 'ON_ERROR_STOP=1', '-c', "SET socp.tenant_id='a'; INSERT INTO tenant_facts VALUES ('b','escape',false)"],
                        capture_output=True, text=True)
                    self.assertNotEqual(denied.returncode, 0)
                    self.assertIn('row-level security', denied.stderr)
                finally:
                    run('pg_ctl', '-D', str(root / 'data'), '-m', 'immediate', '-w', 'stop')


if __name__ == '__main__':
    unittest.main()
