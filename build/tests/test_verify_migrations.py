import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build"
sys.path.insert(0, str(BUILD))
SPEC = importlib.util.spec_from_file_location(
    "verify_migrations", BUILD / "verify-migrations.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


# Hermetic git: no developer identity, signing, or CRLF conversion leaks in.
GIT_CONFIG = [
    "-c", "user.name=Migration Gate",
    "-c", "user.email=migration-gate@example.invalid",
    "-c", "commit.gpgsign=false",
    "-c", "core.autocrlf=false",
    "-c", "init.defaultBranch=main",
]
# Any Flyway location works; this one mirrors services/<module>/src/main/resources/db/migration.
MIGRATION = Path("services") / "demo" / "src" / "main" / "resources" / "db" / "migration"
PUBLISHED = "CREATE TABLE t_demo (id VARCHAR(64) NOT NULL);\n"
POLLUTED = PUBLISHED + "CREATE INDEX IF NOT EXISTS idx_demo_legacy_note ON t_demo (id);\n"


def git(root: Path, *args: str) -> None:
    subprocess.run([*("git", *GIT_CONFIG), *args], cwd=root, check=True, capture_output=True, text=True)


class PublishedMigrationImmutabilityTest(unittest.TestCase):
    """A published Flyway version is immutable: revert it, replay in a newer version."""

    def setUp(self) -> None:
        # tempfile cleanup must not trip over read-only git objects on Windows.
        self._temporary = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(self._temporary.cleanup)
        self.root = Path(self._temporary.name)
        git(self.root, "init", "-q", ".")
        self.write(MIGRATION / "V1__init.sql", PUBLISHED)
        git(self.root, "add", ".")
        git(self.root, "commit", "-q", "-m", "publish V1")

    def write(self, relative: Path, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")

    def commit_all(self, message: str) -> None:
        git(self.root, "add", "-A", ".")
        git(self.root, "commit", "-q", "-m", message)

    def check(self) -> tuple[list[str], list[str]]:
        errors: list[str] = []
        restored = MODULE.check_published_migrations(errors, self.root)
        return errors, restored

    def test_clean_tree_passes(self):
        errors, restored = self.check()
        self.assertEqual([], errors)
        self.assertEqual([], restored)

    def test_pending_in_place_rewrite_fails(self):
        self.write(MIGRATION / "V1__init.sql", POLLUTED)

        errors, restored = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("V1__init.sql", errors[0])
        self.assertIn("immutable", errors[0])
        self.assertIn("new higher version", errors[0])
        self.assertEqual([], restored)

    def test_staged_in_place_rewrite_fails(self):
        self.write(MIGRATION / "V1__init.sql", POLLUTED)
        git(self.root, "add", ".")

        errors, _ = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("immutable", errors[0])

    def test_restore_to_the_published_blob_is_the_sanctioned_remediation(self):
        # The afc86f48 shape: the rewrite was committed, so HEAD itself is wrong.
        self.write(MIGRATION / "V1__init.sql", POLLUTED)
        self.commit_all("rewrite V1 in place")

        self.write(MIGRATION / "V1__init.sql", PUBLISHED)
        self.write(MIGRATION / "V2__demo_replay.sql", POLLUTED[len(PUBLISHED):])

        errors, restored = self.check()

        self.assertEqual([], errors)
        self.assertEqual([MIGRATION.as_posix() + "/V1__init.sql"], restored)

    def test_restore_plus_rewrite_still_fails(self):
        self.write(MIGRATION / "V1__init.sql", POLLUTED)
        self.commit_all("rewrite V1 in place")

        self.write(MIGRATION / "V1__init.sql", PUBLISHED + "-- drifted again\n")

        errors, restored = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("immutable", errors[0])
        self.assertEqual([], restored)

    def test_new_untracked_version_is_allowed(self):
        self.write(MIGRATION / "V2__demo_index.sql", "CREATE INDEX idx_demo ON t_demo (id);\n")

        errors, restored = self.check()

        self.assertEqual([], errors)
        self.assertEqual([], restored)

    def test_deleting_or_renaming_a_published_version_fails(self):
        (self.root / MIGRATION / "V1__init.sql").unlink()

        errors, _ = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("deleted or renamed", errors[0])

    def test_non_migration_sql_is_out_of_scope(self):
        self.write(Path("infra") / "init-sql" / "seed.sql", "SELECT 1;\n")
        self.commit_all("add seed")
        self.write(Path("infra") / "init-sql" / "seed.sql", "SELECT 2;\n")

        errors, restored = self.check()

        self.assertEqual([], errors)
        self.assertEqual([], restored)

    def test_unreadable_repository_fails_closed(self):
        # Never created, so git cannot run there: the gate must refuse to guess.
        missing = self.root / "not-a-repository"

        errors: list[str] = []
        restored = MODULE.check_published_migrations(errors, missing)

        self.assertEqual([], restored)
        self.assertEqual(1, len(errors))
        self.assertIn("cannot read git metadata", errors[0])

    def test_repeatable_migration_may_be_edited_in_place(self):
        # Flyway re-applies R__ files precisely because their checksum changes.
        self.write(MIGRATION / "R__reporting_view.sql", "CREATE OR REPLACE VIEW v_demo AS SELECT id FROM t_demo;\n")
        self.commit_all("add reporting view")
        self.write(MIGRATION / "R__reporting_view.sql", "CREATE OR REPLACE VIEW v_demo AS SELECT id, severity FROM t_demo;\n")

        errors, restored = self.check()

        self.assertEqual([], errors)
        self.assertEqual([], restored)
        self.assertEqual([], MODULE.audit_published_history(self.root))

    def test_history_audit_names_committed_rewrites_only(self):
        # The afc86f48 shape: the rewrite is committed, so no pending diff exists.
        self.write(MIGRATION / "V1__init.sql", POLLUTED)
        self.commit_all("rewrite V1 in place")
        self.write(MIGRATION / "V2__demo_replay.sql", POLLUTED[len(PUBLISHED):])
        self.commit_all("replay the appended statement in V2")

        offenders = MODULE.audit_published_history(self.root)

        # Only the rewrite counts; adding V2 is always legitimate.
        self.assertEqual(1, len(offenders))
        self.assertEqual(
            f"rewrite V1 in place -> {MIGRATION.as_posix()}/V1__init.sql",
            offenders[0].split(" ", 1)[1],
        )

    def test_history_audit_is_clean_when_versions_are_only_added(self):
        self.write(MIGRATION / "V2__demo_index.sql", "CREATE INDEX idx_demo ON t_demo (id);\n")
        self.commit_all("add V2")

        self.assertEqual([], MODULE.audit_published_history(self.root))


if __name__ == "__main__":
    unittest.main()
