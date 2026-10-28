import copy
import importlib.util
from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_load_soak", ROOT / "build/verify-load-soak.py"
)
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


class LoadSoakTest(unittest.TestCase):
    def test_notification_guard_allows_only_the_exact_declared_loopback_fixture(self):
        fixture = "http://127.0.0.1:28421/notify"
        PROBE.verify_notification_targets(
            [
                {"enabled": True, "type": "LOG"},
                {
                    "enabled": False,
                    "type": "SLACK",
                    "target": "https://external.example",
                },
                {"enabled": True, "type": "WEBHOOK", "target": fixture},
            ],
            fixture,
        )
        for channel, allowed in [
            ({"enabled": True, "type": "WEBHOOK", "target": fixture}, ""),
            (
                {"enabled": True, "type": "WEBHOOK", "target": fixture + "/other"},
                fixture,
            ),
            ({"enabled": True, "type": "SLACK", "target": fixture}, fixture),
            (
                {
                    "enabled": True,
                    "type": "WEBHOOK",
                    "target": "https://external.example",
                },
                "https://external.example",
            ),
        ]:
            with self.subTest(channel=channel), self.assertRaises(
                (ValueError, RuntimeError)
            ):
                PROBE.verify_notification_targets([channel], allowed)

    def test_vm_root_without_memory_controller_files_keeps_memory_and_oom_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            cgroup, proc = base / "cgroup", base / "proc"
            cgroup.mkdir()
            proc.mkdir()
            (proc / "meminfo").write_text("MemTotal: 1000 kB\nMemAvailable: 300 kB\n")
            (proc / "vmstat").write_text("pgfault 100\noom_kill 7\n")
            result = PROBE.memory_snapshot(cgroup, proc)
            self.assertEqual(result["memorySource"], "whole-vm-proc")
            self.assertEqual(result["memoryLimitBytes"], 1000 * 1024)
            self.assertEqual(result["workingSetBytes"], 700 * 1024)
            self.assertEqual(result["memoryEvents"]["oom_kill"], "7")
            self.assertIsNone(result["cgroupMemoryBytes"])

    def test_delegated_cgroup_uses_its_own_limit_oom_counter_and_reclaimable_cache(
        self,
    ):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            cgroup, proc = base / "cgroup", base / "proc"
            cgroup.mkdir()
            proc.mkdir()
            (proc / "meminfo").write_text("MemTotal: 1000 kB\nMemAvailable: 300 kB\n")
            for name, value in {
                "memory.current": "800000",
                "memory.max": "900000",
                "memory.stat": "inactive_file 200000\nanon 600000\n",
                "memory.events": "oom_kill 3\n",
            }.items():
                (cgroup / name).write_text(value)
            result = PROBE.memory_snapshot(cgroup, proc)
            self.assertEqual(result["memorySource"], "delegated-cgroup")
            self.assertEqual(result["memoryLimitBytes"], 900000)
            self.assertEqual(result["workingSetBytes"], 600000)
            self.assertEqual(result["memoryEvents"]["oom_kill"], "3")

    def test_import_does_not_create_a_fixture_or_read_credentials(self):
        self.assertIsNone(PROBE.db)
        self.assertIsNone(PROBE.OUT)

    def test_explicit_consent_and_bounded_duration_precede_fixture_access(self):
        with self.assertRaisesRegex(ValueError, "allow-local-fixture"):
            PROBE.initialize(SimpleNamespace(allow_local_fixture=False))
        for seconds, eps in [(59, 5), (3601, 5), (3600, 0), (3600, 26)]:
            with self.subTest(seconds=seconds, eps=eps):
                with self.assertRaisesRegex(ValueError, "duration"):
                    PROBE.initialize(
                        SimpleNamespace(
                            allow_local_fixture=True, seconds=seconds, eps=eps
                        )
                    )

    def test_only_explicit_loopback_endpoints_without_embedded_secrets_are_allowed(
        self,
    ):
        for url in [
            "http://localhost:18092",
            "https://127.0.0.1:9200",
            "http://[::1]:9200",
        ]:
            PROBE.require_loopback(url)
        for url in [
            "https://production.example",
            "http://localhost.example",
            "http://127.0.0.1@production.example",
            "http://user:secret@localhost:9200",
            "file://localhost/etc/passwd",
        ]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                PROBE.require_loopback(url)

    def test_redirect_cannot_forward_fixture_credentials_to_an_external_host(self):
        request = urllib.request.Request(
            "http://127.0.0.1:9200", headers={"Authorization": "Bearer fixture"}
        )
        with self.assertRaises(ValueError):
            PROBE.LoopbackRedirectHandler().redirect_request(
                request, None, 302, "Found", {}, "https://external.example/collect"
            )

    def test_replay_acknowledgement_survives_primary_failure_in_either_order(self):
        for replay_first in [False, True]:
            with self.subTest(replay_first=replay_first), sqlite3.connect(
                ":memory:"
            ) as db:
                db.execute(
                    "create table events(id text primary key,ack integer default 0,received real)"
                )
                db.execute("insert into events(id) values('event-1')")
                failed = {
                    "ok": False,
                    "duplicate": False,
                    "ids": ["event-1"],
                    "received": 10,
                }
                replay = {
                    "ok": True,
                    "duplicate": True,
                    "ids": ["event-1"],
                    "received": 11,
                }
                for result in [replay, failed] if replay_first else [failed, replay]:
                    PROBE.record_acknowledgement(db, result)
                self.assertEqual(
                    db.execute("select ack,received from events").fetchone(), (1, 11)
                )

    def test_failed_replay_cannot_erase_a_successful_primary(self):
        with sqlite3.connect(":memory:") as db:
            db.execute(
                "create table events(id text primary key,ack integer default 0,received real)"
            )
            db.execute("insert into events(id) values('event-1')")
            for ok, duplicate, received in [
                (True, False, 10),
                (False, True, 12),
                (True, True, 13),
            ]:
                PROBE.record_acknowledgement(
                    db,
                    {
                        "ok": ok,
                        "duplicate": duplicate,
                        "ids": ["event-1"],
                        "received": received,
                    },
                )
            self.assertEqual(
                db.execute("select ack,received from events").fetchone(), (1, 10)
            )

    def test_every_capacity_and_integrity_gate_is_required(self):
        passing = {
            "name": "soak",
            "requestErrors": 0,
            "queries": {"errors": 0},
            "reconciliation": {"pass": True},
            "acceptedUnique": 18000,
            "elapsedSeconds": 3600,
            "requestedSeconds": 3600,
            "capacityStable": True,
        }
        self.assertTrue(PROBE.phase_passed(passing, 18000, False))
        mutations = [
            ("requestErrors", 1),
            ("queries", {"errors": 1}),
            ("reconciliation", {"pass": False}),
            ("acceptedUnique", 17639),
            ("elapsedSeconds", 3599.9),
            ("capacityStable", False),
        ]
        for key, value in mutations:
            changed = copy.deepcopy(passing)
            changed[key] = value
            with self.subTest(key=key):
                self.assertFalse(PROBE.phase_passed(changed, 18000, False))
        self.assertFalse(PROBE.phase_passed(passing, 18000, True))

    def test_successful_replay_does_not_hide_an_original_request_error(self):
        result = {
            "name": "soak",
            "requestErrors": 1,
            "queries": {"errors": 0},
            "reconciliation": {"pass": True},
            "acceptedUnique": 18000,
            "elapsedSeconds": 3600,
            "requestedSeconds": 3600,
            "capacityStable": True,
        }
        self.assertFalse(PROBE.phase_passed(result, 18000, False))


if __name__ == "__main__":
    unittest.main()
