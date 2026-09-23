import base64
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build"
sys.path.insert(0, str(BUILD))
import dlq_transport as TRANSPORT

SPEC = importlib.util.spec_from_file_location("replay_dlq", BUILD / "replay-dlq.py")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
Record = TRANSPORT.Record


def envelope(topic="socp-events", partition=2, offset=99, key="broker-key",
             event_id="different-event-id", tenant="acme", payload='{\n  "eventId": "e"\n}'):
    return json.dumps({
        "originalTopic": topic, "partition": partition, "offset": offset,
        "key": key, "eventId": event_id, "tenant": tenant, "schemaVersion": "1",
        "reasonCode": "schema_invalid", "reason": "bad field",
        "originalPayload": payload, "failedAt": "2026-09-20T00:00:00Z",
    }, ensure_ascii=False).encode("utf-8")


class PlanActionsTest(unittest.TestCase):
    def plan(self, value, key=b"dlq-key", topic="socp-events", **options):
        return MODULE.plan_actions([Record(topic + "-dlq", key, value)], topic + "-dlq", **options)[0]

    def test_envelope_preserves_original_key_payload_and_partition(self):
        payload = ' {\r\n\t"message": "中文", "eventId": "other"\n} '
        for key in (None, "", "user-group\t\r\n"):
            with self.subTest(key=key):
                action = self.plan(envelope(key=key, payload=payload))
                self.assertEqual(action.key, None if key is None else key.encode())
                self.assertEqual(action.payload, payload.encode())
                self.assertEqual(action.target_partition, 2)
                self.assertEqual(action.original_offset, 99)
                self.assertEqual(action.record_shape, "envelope")
                self.assertTrue(action.notes)

    def test_raw_records_never_derive_or_normalize_a_key(self):
        for key in (None, b"", b"user\x00\t\r\n\xff"):
            for value in (b"", b" \r\n\t ", b'{"eventId":"new-key"}', b"\x00\xff\r\nbinary"):
                with self.subTest(key=key, value=value):
                    action = self.plan(value, key=key, topic="socp-audit")
                    self.assertFalse(action.skip)
                    self.assertEqual(action.key, key)
                    self.assertEqual(action.payload, value)
                    self.assertEqual(action.target_partition, -1)

    def test_raw_payload_with_one_envelope_named_field_is_not_unwrapped(self):
        value = b'{"originalTopic":"an application field","message":"keep me"}'
        self.assertEqual(self.plan(value, topic="socp-audit").payload, value)

    def test_envelope_requires_typed_complete_metadata_and_correct_target(self):
        malformed = [
            b'{"originalTopic":"socp-events","originalPayload":"data"}',
            envelope(payload={"eventId": "must-not-be-reserialized"}),
            envelope(key=1), envelope(partition=True), envelope(offset=-1),
            envelope(topic="socp-audit"),
        ]
        for value in malformed:
            with self.subTest(value=value):
                action = self.plan(value)
                self.assertTrue(action.skip)
                self.assertIn(action.skip_reason, {
                    "incomplete-indexer-envelope", "invalid-indexer-envelope", "original-topic-mismatch"})

    def test_tombstone_is_distinct_from_empty_and_requires_explicit_opt_in(self):
        self.assertTrue(self.plan(None, topic="socp-audit").skip)
        allowed = self.plan(None, topic="socp-audit", include_tombstones=True)
        self.assertFalse(allowed.skip)
        self.assertIsNone(allowed.output_record().value)
        self.assertFalse(self.plan(b"", topic="socp-audit").skip)

    def test_routed_header_loss_is_always_blocked(self):
        record = Record("socp-detection-routed-v2-dlq", b"k", b"{}",
                        headers=(("routing", b"not-proof-of-original-headers"),))
        action = MODULE.plan_actions([record], record.topic, include_tombstones=True)[0]
        self.assertTrue(action.skip)
        self.assertEqual(action.skip_reason, "routing-header-not-persisted")

    def test_dedup_requires_same_origin_bytes_headers_and_tenant(self):
        first = Record("socp-events-dlq", b"dlq", envelope(), offset=1)
        same_original = Record(first.topic, first.key, first.value, offset=2)
        other_original = Record(first.topic, first.key, envelope(offset=100), offset=3)
        other_tenant = Record(first.topic, first.key, envelope(tenant="other"), offset=4)
        other_headers = Record(first.topic, first.key, first.value, headers=(("h", b"new"),))
        actions = MODULE.plan_actions([first, same_original, other_original, other_tenant, other_headers], first.topic)
        self.assertEqual([a.deduped for a in actions], [False, True, False, False, False])
        self.assertFalse(any(a.skip for a in MODULE.plan_actions([first, same_original], first.topic, dedup=False)))

    def test_independent_raw_observations_are_not_collapsed(self):
        records = [Record("socp-audit-dlq", b"same-group", b"same value", offset=offset) for offset in (1, 2)]
        self.assertFalse(any(a.skip for a in MODULE.plan_actions(records, records[0].topic)))
        duplicates = MODULE.plan_actions([records[0], records[0]], records[0].topic)
        self.assertTrue(duplicates[1].deduped)

    def test_missing_key_is_not_deduplicated(self):
        row = Record("socp-audit-dlq", None, b"same")
        self.assertFalse(any(a.skip for a in MODULE.plan_actions([row, row], row.topic)))

    def test_headers_keep_order_duplicate_names_and_null_values(self):
        headers = (("same", b"one"), ("same", None), ("same", b""), ("unicode-名", b"\xff"))
        row = Record("socp-audit-dlq", b"k", b"v", headers=headers)
        self.assertEqual(MODULE.plan_actions([row], row.topic)[0].output_record().headers, headers)

    def test_topic_names_and_input_topic_binding_are_validated(self):
        self.assertEqual(MODULE.normalise_dlq_topic("socp-audit"), "socp-audit-dlq")
        for name in ("", ".", "..", "space topic", "x" * 250):
            with self.subTest(name=name), self.assertRaises(ValueError):
                TRANSPORT.topic_name(name)
        with self.assertRaises(ValueError):
            MODULE.main_topic_for("no-suffix")
        with self.assertRaises(ValueError):
            MODULE.plan_actions([Record("other-dlq", b"k", b"v")], "socp-audit-dlq")


class FileAndWireTest(unittest.TestCase):
    def rows(self):
        return [
            Record("socp-audit-dlq", None, b"pretty\r\n\t\xff\x00", 0, 1, 123,
                   (("duplicate", None), ("duplicate", b""), ("duplicate", b"a\nb"))),
            Record("socp-audit-dlq", b"", None, 1, 2, 124),
            Record("socp-audit-dlq", b"\xff\t\r\n", b"", 2, 3, 125),
        ]

    def test_file_and_wire_round_trip_preserve_all_bytes_and_nulls(self):
        rows = self.rows()
        self.assertEqual(TRANSPORT.decode_wire(TRANSPORT.encode_wire(rows)), rows)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "batch.json"
            TRANSPORT.write_batch(path, rows, "socp-audit-dlq")
            data = json.loads(path.read_text())
            self.assertIsNone(data["records"][0]["keyBase64"])
            self.assertEqual(data["records"][1]["keyBase64"], "")
            self.assertIsNone(data["records"][1]["valueBase64"])
            self.assertEqual(TRANSPORT.read_batch(path, "socp-audit-dlq"), rows)
            with self.assertRaises(FileExistsError):
                TRANSPORT.write_batch(path, [], "socp-audit-dlq")
            self.assertEqual(TRANSPORT.read_batch(path, "socp-audit-dlq"), rows)

    def test_wire_truncation_trailing_and_oversized_fields_fail(self):
        encoded = TRANSPORT.encode_wire(self.rows())
        for data in (b"", encoded[:-1], encoded + b"tail",
                     TRANSPORT.MAGIC + struct.pack(">i", 10001),
                     TRANSPORT.MAGIC + struct.pack(">ii", 1, 2**31-1)):
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                TRANSPORT.decode_wire(data)

    def test_invalid_file_fields_base64_duplicates_and_legacy_tsv_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "batch.json"
            TRANSPORT.write_batch(path, self.rows(), "socp-audit-dlq")
            original = json.loads(path.read_text())
            cases = ["key\tvalue\n", '{"format":1,"format":2}', '{"records":[', "NaN"]
            bad = json.loads(json.dumps(original))
            bad["records"][0]["keyBase64"] = "%%"
            cases.append(json.dumps(bad))
            bad = json.loads(json.dumps(original))
            bad["records"][0]["offset"] = True
            cases.append(json.dumps(bad))
            bad = json.loads(json.dumps(original))
            bad["records"][0]["surprise"] = "ignored?"
            cases.append(json.dumps(bad))
            for text in cases:
                path.write_text(text, encoding="utf-8")
                with self.subTest(text=text[:40]), self.assertRaises(ValueError):
                    TRANSPORT.read_batch(path, "socp-audit-dlq")

    def test_record_and_header_bounds_are_enforced(self):
        for record in (
            Record("socp-audit-dlq", b"k", b"x" * TRANSPORT.MAX_RECORD_BYTES),
            Record("socp-audit-dlq", b"k", b"v", headers=(("h", b""),) * 257),
            Record("socp-audit-dlq", b"k", b"v", headers=(("x" * 257, b""),)),
        ):
            with self.subTest(headers=len(record.headers)), self.assertRaises(ValueError):
                TRANSPORT.encode_wire([record])
        records = [Record("socp-audit-dlq", b"k", b"", headers=(("h", b""),) * 256)] * 17
        with self.assertRaises(ValueError):
            TRANSPORT.encode_wire(records)

    def test_file_import_counts_header_bytes_and_headers_across_records(self):
        rows = [Record("socp-audit-dlq", b"", b"", offset=i, headers=(("h", b"1234567890"),)) for i in range(3)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "batch.json"
            TRANSPORT.write_batch(path, rows, "socp-audit-dlq")
            for limit, value in (("MAX_BATCH_BYTES", 50), ("MAX_BATCH_HEADERS", 2)):
                with self.subTest(limit=limit), mock.patch.object(TRANSPORT, limit, value), self.assertRaises(ValueError):
                    TRANSPORT.read_batch(path, "socp-audit-dlq")


class ProcessLifecycleTest(unittest.TestCase):
    def test_stderr_is_drained_without_deadlock_and_not_returned_as_data(self):
        command = [sys.executable, "-c",
                   "import sys; sys.stderr.buffer.write(b'x'*1048576); sys.stderr.flush(); sys.stdout.buffer.write(b'ok')"]
        self.assertEqual(TRANSPORT.run_process(command, None, 5), b"ok")

    def test_partial_output_then_nonzero_exit_is_not_a_successful_batch(self):
        command = [sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'partial'); sys.exit(2)"]
        with self.assertRaisesRegex(TRANSPORT.TransportError, "exit 2"):
            TRANSPORT.run_process(command, None, 5)

    def test_timeout_and_output_overflow_kill_and_reap_child(self):
        actual_popen = TRANSPORT.subprocess.Popen
        processes = []
        def spawn(*args, **kwargs):
            process = actual_popen(*args, **kwargs)
            processes.append(process)
            return process
        with mock.patch.object(TRANSPORT.subprocess, "Popen", side_effect=spawn):
            with self.assertRaisesRegex(TRANSPORT.TransportError, "deadline exceeded"):
                TRANSPORT.run_process([sys.executable, "-c", "import time; time.sleep(30)"], None, 0.2)
            with self.assertRaisesRegex(TRANSPORT.TransportError, "output limit exceeded"):
                TRANSPORT.run_process([sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'x'*1000000)"],
                                      None, 5, output_limit=10)
        for process in processes:
            self.assertIsNotNone(process.poll())
            self.assertTrue(process.stdout.closed)
            self.assertTrue(process.stderr.closed)

    def test_broken_input_pipe_cannot_report_success(self):
        with self.assertRaises(TRANSPORT.TransportError):
            TRANSPORT.run_process([sys.executable, "-c", "pass"], b"x" * 1048576, 5)

    def test_interruption_reaps_child_and_releases_pipes(self):
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        wait = process.wait
        calls = 0
        def interrupted_wait(*args, **kwargs):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise KeyboardInterrupt()
            return wait(*args, **kwargs)
        with mock.patch.object(TRANSPORT.subprocess, "Popen", return_value=process), \
                mock.patch.object(process, "wait", side_effect=interrupted_wait):
            with self.assertRaises(KeyboardInterrupt):
                TRANSPORT.run_process(["unused"], None, 5)
        self.assertIsNotNone(process.poll())
        self.assertTrue(process.stdout.closed)
        self.assertTrue(process.stderr.closed)

    def test_producer_requires_exact_ack_and_warns_about_partial_side_effects(self):
        args = MODULE.build_parser().parse_args(["--topic", "socp-audit"])
        action = MODULE.plan_actions([Record("socp-audit-dlq", b"k", b"v")], "socp-audit-dlq")
        with mock.patch.object(MODULE, "bridge_command", return_value=["fake"]), \
             mock.patch.object(MODULE, "run_process", return_value=b"ACK\t0\n"):
            with self.assertRaisesRegex(TRANSPORT.TransportError, "some records may already"):
                MODULE.produce_actions(action, args, "socp-audit")


class MainCliTest(unittest.TestCase):
    def run_main(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = MODULE.main(argv)
        return code, out.getvalue(), err.getvalue()

    def input_file(self, directory, rows, topic="socp-audit-dlq"):
        path = Path(directory) / "source.json"
        TRANSPORT.write_batch(path, rows, topic)
        return str(path)

    def test_dry_run_preserves_default_source_limit_and_no_network(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.input_file(directory, [Record("socp-audit-dlq", str(i).encode(), b"{}", offset=i) for i in range(101)])
            with mock.patch.object(MODULE, "produce_actions") as produce:
                code, out, err = self.run_main(["--topic", "socp-audit", "--input-file", path, "--dry-run"])
            self.assertEqual(code, 0)
            self.assertIn("100 records", err)
            self.assertNotIn("key=b'100'", out)
            produce.assert_not_called()

    def test_detection_guards_apply_to_force_dry_run_and_output(self):
        for topic, value in (("socp-events", envelope()), ("socp-detection-routed-v2", b"{}")):
            with self.subTest(topic=topic), tempfile.TemporaryDirectory() as directory:
                path = self.input_file(directory, [Record(topic + "-dlq", b"k", value)], topic + "-dlq")
                output = Path(directory) / "reviewed.json"
                args = ["--topic", topic, "--input-file", path, "--output-file", str(output), "--dry-run"]
                code, _, err = self.run_main(args)
                self.assertEqual(code, 3)
                self.assertIn("BLOCKED", err)
                self.assertFalse(output.exists())
                code, _, _ = self.run_main(args + ["--force"])
                self.assertEqual(code, 3 if topic.endswith("routed-v2") else 0)

    def test_export_keeps_blocked_source_bytes_without_force(self):
        with tempfile.TemporaryDirectory() as directory:
            rows = [Record("socp-detection-routed-v2-dlq", None, b"\xff\n")]
            path = self.input_file(directory, rows, rows[0].topic)
            output = Path(directory) / "capture.json"
            code, _, _ = self.run_main(["--topic", rows[0].topic, "--input-file", path, "--export-file", str(output)])
            self.assertEqual(code, 0)
            self.assertEqual(TRANSPORT.read_batch(output, rows[0].topic), rows)

    def test_selected_export_keeps_source_envelope_so_reimport_does_not_unwrap_twice(self):
        inner = envelope(topic="socp-audit").decode()
        row = Record("socp-events-dlq", b"wrapper", envelope(payload=inner))
        with tempfile.TemporaryDirectory() as directory:
            path = self.input_file(directory, [row], row.topic)
            output = Path(directory) / "selected.json"
            code, _, _ = self.run_main(["--topic", row.topic, "--input-file", path,
                                        "--output-file", str(output), "--force"])
            self.assertEqual(code, 0)
            exported = TRANSPORT.read_batch(output, row.topic)
            self.assertEqual(exported, [row])
            self.assertEqual(MODULE.plan_actions(exported, row.topic)[0].payload, inner.encode())

    def test_invalid_cli_bounds_and_incomplete_cursor_fail_before_io(self):
        for suffix in (["--limit", "0"], ["--limit", "10001"], ["--timeout-ms", "0"],
                       ["--timeout-ms", "120001"], ["--partition", "1"], ["--offset", "-1"]):
            with self.subTest(suffix=suffix), self.assertRaises(SystemExit) as failure:
                self.run_main(["--topic", "socp-audit", "--input-file", "missing.json"] + suffix)
            self.assertEqual(failure.exception.code, 2)

    def test_consumer_failure_prevents_any_publish(self):
        with mock.patch.object(MODULE, "broker_records", side_effect=TRANSPORT.TransportError("read failed")), \
             mock.patch.object(MODULE, "produce_actions") as produce:
            code, _, err = self.run_main(["--topic", "socp-audit"])
        self.assertEqual(code, 1)
        self.assertIn("read failed", err)
        produce.assert_not_called()


if __name__ == "__main__":
    unittest.main()
