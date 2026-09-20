import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
import contextlib


ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build"
SPEC = importlib.util.spec_from_file_location(
    "replay_dlq", BUILD / "replay-dlq.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def envelope(topic="socp-events", partition=2, offset=99, key="evt-1",
             event_id="evt-1", tenant="acme", reason="schema_invalid",
             payload='{"eventId":"evt-1","tenantId":"acme"}'):
    import json
    return json.dumps({
        "originalTopic": topic, "partition": partition, "offset": offset,
        "key": key, "eventId": event_id, "tenant": tenant, "schemaVersion": "1",
        "reasonCode": reason, "reason": "bad field", "originalPayload": payload,
        "failedAt": "2026-09-20T00:00:00Z",
    }, separators=(",", ":"))


class TopicNormalisationTest(unittest.TestCase):
    def test_main_and_dlq_round_trip(self):
        self.assertEqual(MODULE.normalise_dlq_topic("socp-events"), "socp-events-dlq")
        self.assertEqual(MODULE.normalise_dlq_topic("socp-events-dlq"), "socp-events-dlq")
        self.assertEqual(MODULE.main_topic_for("socp-events-dlq"), "socp-events")

    def test_main_topic_for_rejects_non_dlq(self):
        with self.assertRaises(ValueError):
            MODULE.main_topic_for("socp-events")

    def test_unknown_main_topic_is_documented(self):
        # The planner only special-cases detect topics; every other main topic is a
        # plain replay. Keep the roster in sync with create-topics.sh.
        self.assertIn("socp-detection-routed-v2", MODULE.DETECTION_SKIP_TOPICS)
        self.assertEqual(
            set(MODULE.MAIN_TOPICS),
            {"socp-events", "socp-detection-routed-v2", "socp-alarm-events",
             "socp-alarm-original", "socp-rule-changes", "socp-audit"})


class ClassifyTest(unittest.TestCase):
    def test_envelope_extracts_original_payload_and_id(self):
        shape, key, payload, partition, offset, reason = MODULE.classify("k", envelope())
        self.assertEqual(shape, "envelope")
        self.assertEqual(key, "evt-1")
        self.assertEqual(payload, '{"eventId":"evt-1","tenantId":"acme"}')
        self.assertEqual((partition, offset), (2, 99))
        self.assertEqual(reason, "schema_invalid")

    def test_raw_json_prefers_event_id_as_key(self):
        value = '{"eventId":"evt-9","fields":{"tenant_id":"acme"}}'
        shape, key, payload, *_ = MODULE.classify("broker-key", value)
        self.assertEqual(shape, "raw")
        self.assertEqual(key, "evt-9")
        self.assertEqual(payload, value)

    def test_raw_json_without_id_keeps_broker_key(self):
        shape, key, payload, *_ = MODULE.classify("broker-key", '{"a":1}')
        self.assertEqual(shape, "raw")
        self.assertEqual(key, "broker-key")

    def test_non_json_passes_through(self):
        shape, key, payload, *_ = MODULE.classify("k", "not json at all")
        self.assertEqual(shape, "unparseable")
        self.assertEqual(payload, "not json at all")

    def test_empty_value(self):
        shape, key, payload, partition, offset, reason = MODULE.classify("k", "   ")
        self.assertEqual(shape, "unparseable")
        self.assertEqual(payload, "")
        self.assertEqual(reason, "empty")


class PlanActionsTest(unittest.TestCase):
    def test_targets_main_topic_and_replays_original_payload(self):
        actions = MODULE.plan_actions([(None, envelope())], "socp-events-dlq")
        self.assertEqual(len(actions), 1)
        action = actions[0]
        self.assertEqual(action.target_topic, "socp-events")
        self.assertEqual(action.key, "evt-1")
        self.assertEqual(action.payload, '{"eventId":"evt-1","tenantId":"acme"}')
        self.assertFalse(action.skip)
        self.assertTrue(any("journal" in n for n in action.notes),
                        "detect path must carry the journal-reset caveat")

    def test_dedup_collapses_repeated_id_within_batch(self):
        records = [(None, envelope(offset=1)), (None, envelope(offset=2))]
        deduped = MODULE.plan_actions(records, "socp-events-dlq", dedup=True)
        self.assertEqual(sum(1 for a in deduped if not a.skip), 1)
        self.assertEqual(sum(1 for a in deduped if a.deduped), 1)
        kept = MODULE.plan_actions(records, "socp-events-dlq", dedup=False)
        self.assertEqual(sum(1 for a in kept if not a.skip), 2)

    def test_routed_v2_is_skipped_for_header_loss(self):
        actions = MODULE.plan_actions([("evt", '{"eventId":"evt"}')], "socp-detection-routed-v2-dlq")
        self.assertTrue(all(a.skip for a in actions))
        self.assertTrue(all(a.skip_reason == "routing-header-not-persisted" for a in actions))

    def test_non_detect_topic_gets_no_journal_note(self):
        actions = MODULE.plan_actions([("k", '{"eventId":"e1"}')], "socp-audit-dlq")
        self.assertEqual(actions[0].target_topic, "socp-audit")
        self.assertEqual(actions[0].notes, [])
        self.assertFalse(actions[0].skip)

    def test_empty_records_are_skipped(self):
        actions = MODULE.plan_actions([("k", "  ")], "socp-audit-dlq")
        self.assertTrue(actions[0].skip)
        self.assertEqual(actions[0].skip_reason, "empty-or-tombstone")


class MainCliTest(unittest.TestCase):
    def run_main(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = MODULE.main(argv)
        return code, out.getvalue(), err.getvalue()

    def write_input(self, rows):
        handle = tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False, encoding="utf-8")
        for key, value in rows:
            handle.write(f"{key}\t{value}\n")
        handle.close()
        self.addCleanup(lambda: Path(handle.name).unlink(missing_ok=True))
        return handle.name

    def test_dry_run_reports_without_producing(self):
        path = self.write_input([("evt-1", envelope())])
        code, out, err = self.run_main(["--topic", "socp-events-dlq",
                                        "--input-file", path, "--dry-run", "--force"])
        self.assertEqual(code, 0)
        self.assertIn("SEND", out)
        self.assertIn("dry-run", err)

    def test_detect_topic_blocked_without_force(self):
        path = self.write_input([("evt-1", envelope())])
        code, out, err = self.run_main(["--topic", "socp-events-dlq",
                                        "--input-file", path, "--dry-run"])
        self.assertEqual(code, 3)
        self.assertIn("BLOCKED", err)

    def test_routed_v2_never_forced(self):
        path = self.write_input([("evt", '{"eventId":"evt"}')])
        code, out, err = self.run_main(["--topic", "socp-detection-routed-v2-dlq",
                                        "--input-file", path, "--dry-run", "--force"])
        self.assertEqual(code, 3)
        self.assertIn("misroute", err)

    def test_output_file_writes_replayable_lines(self):
        path = self.write_input([("evt-1", envelope())])
        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "replay.tsv"
            code, _, _ = self.run_main(["--topic", "socp-events-dlq",
                                        "--input-file", path, "--output-file", str(out_path),
                                        "--force"])
            self.assertEqual(code, 0)
            content = out_path.read_text(encoding="utf-8").strip().splitlines()
            self.assertEqual(len(content), 1)
            key, _, payload = content[0].partition("\t")
            self.assertEqual(key, "evt-1")
            self.assertEqual(payload, '{"eventId":"evt-1","tenantId":"acme"}')


if __name__ == "__main__":
    unittest.main()
