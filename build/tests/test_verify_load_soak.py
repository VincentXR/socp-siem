import copy
import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import threading
import ssl
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_load_soak", ROOT / "build/verify-load-soak.py"
)
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)


class LoadSoakTest(unittest.TestCase):
    def test_http_clients_reuse_trust_setup_without_sharing_threads_or_trust_modes(self):
        with patch.object(PROBE, "http_clients", threading.local()):
            verified = PROBE.http_opener(False)
            fixture = PROBE.http_opener(True)
            self.assertIs(verified, PROBE.http_opener(False))
            self.assertIs(fixture, PROBE.http_opener(True))
            self.assertIsNot(verified, fixture)
            for opener, expected in [(verified, ssl.CERT_REQUIRED), (fixture, ssl.CERT_NONE)]:
                handler = next(h for h in opener.handlers if isinstance(h, urllib.request.HTTPSHandler))
                # Older Python versions defer creation of the verified default
                # context to HTTPSConnection; None still means normal verification.
                if handler._context is None:
                    self.assertEqual(expected, ssl.CERT_REQUIRED)
                else:
                    self.assertEqual(handler._context.verify_mode, expected)
                self.assertTrue(any(isinstance(h, PROBE.LoopbackRedirectHandler) for h in opener.handlers))
            others = []
            thread = threading.Thread(target=lambda: others.append(PROBE.http_opener(False)))
            thread.start()
            thread.join(timeout=5)
            self.assertFalse(thread.is_alive())
            self.assertEqual(len(others), 1)
            self.assertIsNot(verified, others[0])

    def test_capacity_plan_requires_separate_mode_and_bounds_rate_volume_and_time(self):
        args = SimpleNamespace(mode="capacity", seconds=120, stages="250,500,1000,2000")
        self.assertEqual(PROBE.load_plan(args)[-1], ("capacity-2000", 2000, 120))
        for seconds, stages in [(121, "250"), (120, "250,500,1000,2000,3000"),
                                (60, "3001"), (60, "2000,500"), (60, "0")]:
            with self.subTest(seconds=seconds, stages=stages), self.assertRaises(ValueError):
                PROBE.load_plan(SimpleNamespace(mode="capacity", seconds=seconds, stages=stages))
        self.assertEqual(PROBE.lag_limit("capacity-2000"), 10000)
        self.assertEqual(PROBE.lag_limit("pressure-100"), 1000)
        self.assertTrue(PROBE.is_load_phase("capacity-2000"))
        self.assertFalse(PROBE.is_load_phase("capacity-2000-drain"))

    def test_declared_warmup_is_bounded_counted_and_run_before_measured_stages(self):
        args = SimpleNamespace(mode="capacity", seconds=120, stages="250,500,1000,2000",
                               warmup_eps=50, warmup_seconds=120)
        plan = PROBE.load_plan(args)
        with patch.object(PROBE, "run_phase", return_value=(6000, {"pass": True})) as phase:
            with patch.object(PROBE.guard, "is_set", return_value=False):
                PROBE.run_measurements(plan, PROBE.warmup_plan(args))
        self.assertEqual(phase.call_args_list[0].args, ("warmup", 50, 120, 0))
        self.assertEqual(phase.call_args_list[1].args, ("capacity-250", 250, 120, 6000))
        for eps, seconds in [(0, 20), (101, 20), (50, 0), (50, 121)]:
            with self.subTest(eps=eps, seconds=seconds), self.assertRaises(ValueError):
                PROBE.warmup_plan(SimpleNamespace(warmup_eps=eps, warmup_seconds=seconds))
        for mode, seconds, eps, stages in [("pressure", 500, 1, "99,100"),
                                           ("soak", 3600, 25, "")]:
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                PROBE.load_plan(SimpleNamespace(mode=mode, seconds=seconds, eps=eps, stages=stages,
                                               warmup_eps=100, warmup_seconds=120))

    def test_process_cpu_counters_handle_parentheses_and_exiting_processes(self):
        with tempfile.TemporaryDirectory() as temporary:
            proc = Path(temporary)
            (proc / "42").mkdir()
            (proc / "43").mkdir()
            (proc / "stat").write_text("aggregate is not a process")
            (proc / "42" / "stat").write_text(
                "42 (java (worker)) S 1 2 3 4 5 6 7 8 9 10 100 50 13 14 15 16 17 18 12345 20\n")
            with patch.object(PROBE.os, "sysconf", return_value=100, create=True):
                counters = PROBE.process_cpu_counters(proc)
            self.assertEqual(counters, {"42": {
                "name": "java (worker)", "startTicks": 12345, "cpuSeconds": 1.5}})

    def test_capacity_addresses_are_valid_and_do_not_overlap_old_pressure_blocks(self):
        import ipaddress
        blocks = PROBE.available_ip_blocks([(1000, 1000), (8192, 8192)], 8192)
        self.assertNotIn(0, blocks)
        self.assertNotIn(8192, blocks)
        self.assertIn(122880, blocks)
        with patch.object(PROBE, "ip_base", blocks[-1]):
            first = PROBE.event(95)[0]["src_ip"]
            last = PROBE.event(499995)[0]["src_ip"]
            self.assertNotEqual(first, last)
            self.assertIn(ipaddress.ip_address(last), ipaddress.ip_network("198.18.0.0/15"))

    def test_pressure_plan_is_bounded_before_fixture_access(self):
        args = SimpleNamespace(mode="pressure", seconds=300, stages="10,25,50,100")
        self.assertEqual(
            PROBE.load_plan(args),
            [(f"pressure-{eps}", eps, 300) for eps in (10, 25, 50, 100)],
        )
        for seconds, stages in [
            (59, "10"),
            (601, "10"),
            (300, "0"),
            (300, "101"),
            (300, "10,10"),
            (300, "25,10"),
            (300, "1,2,3,4,5,6"),
            (300, "10,bad"),
            (300, ""),
            (600, "10,25,50,100"),
        ]:
            with self.subTest(seconds=seconds, stages=stages), self.assertRaises(
                ValueError
            ):
                PROBE.load_plan(
                    SimpleNamespace(mode="pressure", seconds=seconds, stages=stages)
                )

    def test_failed_stage_stops_escalation_and_warmup_failure_stops_all_load(self):
        plan = [
            ("pressure-10", 10, 300),
            ("pressure-25", 25, 300),
            ("pressure-50", 50, 300),
        ]
        with patch.object(
            PROBE,
            "run_phase",
            side_effect=[
                (200, {"pass": True}),
                (3200, {"pass": True}),
                (4000, {"pass": False}),
            ],
        ) as phase:
            PROBE.run_measurements(plan)
            self.assertEqual(
                [call.args[0] for call in phase.call_args_list],
                ["warmup", "pressure-10", "pressure-25"],
            )
        with patch.object(
            PROBE, "run_phase", return_value=(100, {"pass": False})
        ) as phase:
            PROBE.run_measurements(plan)
            self.assertEqual(phase.call_count, 1)

    def test_resource_guard_stops_escalation_even_after_a_passing_stage(self):
        with patch.object(
            PROBE, "run_phase", return_value=(200, {"pass": True})
        ) as phase:
            with patch.object(PROBE.guard, "is_set", side_effect=[False, True]):
                PROBE.run_measurements(
                    [("pressure-10", 10, 300), ("pressure-25", 25, 300)]
                )
            self.assertEqual(
                [call.args[0] for call in phase.call_args_list],
                ["warmup", "pressure-10"],
            )

    def test_pressure_lag_guard_applies_to_load_but_not_recovery(self):
        for name in ["soak", "pressure-10", "pressure-100"]:
            self.assertTrue(PROBE.is_load_phase(name))
        for name in ["warmup", "pressure-10-drain", "soak-drain", "final"]:
            self.assertFalse(PROBE.is_load_phase(name))

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
            "queries": {"errors": 0, "count": 10},
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
            ("elapsedSeconds", 3700),
            ("queries", {"errors": 0, "count": 0}),
            ("capacityStable", False),
            ("monitorErrors", 1),
            ("healthFailures", 1),
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
