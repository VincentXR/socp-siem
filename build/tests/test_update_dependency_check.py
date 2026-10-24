import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("nvd_update", ROOT / "build/update-dependency-check.py")
UPDATE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(UPDATE)


class UpdateDependencyCheckTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.data = self.root / "cache"
        self.data.mkdir()
        (self.data / "odc.mv.db").write_text("known-good")
        self.logs = self.root / "logs"

    def run_update(self, body, attempts=3):
        stub = self.root / "updater.py"
        stub.write_text(
            "import sys\nfrom pathlib import Path\n"
            "data = Path(sys.argv[-1].split('=', 1)[1])\n"
            "counter = Path(sys.argv[1])\n"
            "attempt = int(counter.read_text()) + 1 if counter.exists() else 1\n"
            "counter.write_text(str(attempt))\n" + body
        )
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            result = UPDATE.update_database(
                self.data, self.logs, [sys.executable, str(stub), str(self.root / "count")],
                attempts=attempts, retry_delay=0,
            )
        return result, int((self.root / "count").read_text())

    def test_log_is_flushed_before_the_child_finishes(self):
        log = self.root / "live.log"

        def child_output():
            yield "verified feed 2023\n"
            # Forced CI cancellation can occur before process exit/close. The
            # last completed line must already be available to artifact upload.
            self.assertEqual("verified feed 2023\n", log.read_text())

        process = mock.MagicMock()
        process.stdout = child_output()
        process.wait.return_value = 0
        process.__enter__.return_value = process
        with mock.patch.object(UPDATE.subprocess, "Popen", return_value=process), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, UPDATE.run_logged(["updater"], log))

    def test_truncated_feed_retries_from_clean_cache_and_publishes_only_success(self):
        result, attempts = self.run_update(
            "assert (data / 'odc.mv.db').read_text() == 'known-good'\n"
            "(data / 'odc.mv.db').write_text('partial' if attempt == 1 else 'complete')\n"
            "print('DownloadFailedException: Premature end of Content-Length body')\n"
            "sys.exit(1 if attempt == 1 else 0)\n"
        )
        self.assertEqual((0, 2), (result, attempts))
        self.assertEqual("complete", (self.data / "odc.mv.db").read_text())
        self.assertEqual(2, len(list(self.logs.glob("update-attempt-*.log"))))

    def test_exhausted_download_retries_preserve_previous_database(self):
        result, attempts = self.run_update(
            "(data / 'odc.mv.db').write_text('partial')\n"
            "print('DownloadFailedException: read truncated')\nsys.exit(2)\n"
        )
        self.assertEqual((2, 3), (result, attempts))
        self.assertEqual("known-good", (self.data / "odc.mv.db").read_text())

    def test_database_error_is_not_retried(self):
        result, attempts = self.run_update("print('Database corruption detected')\nsys.exit(1)\n")
        self.assertEqual((1, 1), (result, attempts))
        self.assertEqual("known-good", (self.data / "odc.mv.db").read_text())

    def test_zero_exit_with_failed_cve_import_preserves_previous_database(self):
        result, attempts = self.run_update(
            "(data / 'odc.mv.db').write_text('missing-cve')\n"
            "print('[ERROR] Failed to process CVE-2026-12345')\n"
        )
        self.assertEqual((1, 1), (result, attempts))
        self.assertEqual("known-good", (self.data / "odc.mv.db").read_text())

    def test_zero_exit_with_other_updater_error_fails_closed(self):
        result, attempts = self.run_update(
            "(data / 'odc.mv.db').write_text('incomplete')\n"
            "print('[\\x1b[31mERROR\\x1b[0m] Failed to store vulnerability metadata')\n"
        )
        self.assertEqual((1, 1), (result, attempts))
        self.assertEqual("known-good", (self.data / "odc.mv.db").read_text())

    def test_success_without_database_fails_closed(self):
        (self.data / "odc.mv.db").unlink()
        result, attempts = self.run_update("print('No database was created')\n")
        self.assertEqual((1, 1), (result, attempts))
        self.assertFalse((self.data / "odc.mv.db").exists())

    def test_failed_cold_start_does_not_publish_partial_database(self):
        (self.data / "odc.mv.db").unlink()
        self.data.rmdir()
        result, attempts = self.run_update(
            "(data / 'odc.mv.db').write_text('partial')\n"
            "print('DownloadFailedException')\nsys.exit(1)\n", attempts=1
        )
        self.assertEqual((1, 1), (result, attempts))
        self.assertFalse(self.data.exists())

    def test_invalid_feed_snapshot_never_starts_database_import(self):
        arguments = ["update-dependency-check.py", "--data-directory", str(self.data),
                     "--log-directory", str(self.logs)]
        with mock.patch.object(sys, "argv", arguments), \
                mock.patch.object(UPDATE, "run_logged", return_value=1), \
                mock.patch.object(UPDATE, "update_database") as update, \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, UPDATE.main())
        update.assert_not_called()
        self.assertEqual("known-good", (self.data / "odc.mv.db").read_text())

    def test_verified_snapshot_uses_file_feed_without_escaping_placeholder(self):
        arguments = ["update-dependency-check.py", "--data-directory", str(self.data),
                     "--log-directory", str(self.logs), "--feed-cache-directory", str(self.root / "payloads")]
        with mock.patch.object(sys, "argv", arguments), \
                mock.patch.object(UPDATE, "run_logged", return_value=0) as stage, \
                mock.patch.object(UPDATE, "update_database", return_value=0) as update:
            self.assertEqual(0, UPDATE.main())
        stage_command = stage.call_args.args[0]
        self.assertEqual(str(self.root / "payloads"), stage_command[stage_command.index("--cache-directory") + 1])
        command = update.call_args.args[2]
        feed = next(argument for argument in command if argument.startswith("-DnvdDatafeedUrl="))
        self.assertTrue(feed.startswith("-DnvdDatafeedUrl=file:///"))
        self.assertTrue(feed.endswith("/nvdcve-2.0-{0}.json.gz"))
        self.assertNotIn("modified", feed)
        self.assertIn("-DnvdValidForHours=0", command)
        self.assertIn("-DfailOnError=true", command)


if __name__ == "__main__":
    unittest.main()
