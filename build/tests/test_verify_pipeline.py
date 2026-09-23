import contextlib
import importlib.util
import io
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("verify_pipeline", ROOT / "build/verify-pipeline.py")
PIPELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PIPELINE)


def record(value, offset=21):
    return SimpleNamespace(value=value, key=b"key", partition=0, offset=offset)


class PipelineProbeTest(unittest.TestCase):
    def setUp(self):
        self.output = io.StringIO()
        self.addCleanup(patch.stopall)
        patch.object(PIPELINE, "TENANT", "default").start()
        patch.object(PIPELINE, "TOPIC", "socp-events").start()

    def consumer(self):
        consumer = Mock()
        consumer.partitions_for_topic.return_value = {0, 1}
        consumer.end_offsets.return_value = {("socp-events", 0): 21, ("socp-events", 1): 40}
        consumer.poll.return_value = {}
        return consumer

    def kafka(self, consumer):
        factory = Mock(return_value=consumer)
        probe = PIPELINE.KafkaProbe(factory, lambda topic, number: (topic, number))
        return probe, factory

    def test_kafka_starts_at_each_pre_injection_end_without_group_or_commit(self):
        consumer = self.consumer()
        kafka, factory = self.kafka(consumer)
        kwargs = factory.call_args.kwargs
        self.assertIsNone(kwargs["group_id"])
        self.assertFalse(kwargs["enable_auto_commit"])
        self.assertFalse(kwargs["allow_auto_create_topics"])
        self.assertEqual(kwargs["isolation_level"], "read_committed")
        self.assertEqual(consumer.seek.call_args_list[0].args, (("socp-events", 0), 21))
        self.assertEqual(consumer.seek.call_args_list[1].args, (("socp-events", 1), 40))
        probe = PIPELINE.probe_event()
        matching = {**probe, "tenantId": "default", "msg": probe["message"]}
        values = [None, b"not JSON", b"123", json.dumps({**matching, "eventId": "old"}).encode(),
                  json.dumps({**matching, "tenantId": "other"}).encode(),
                  json.dumps({**matching, "host": "old-host"}).encode(), json.dumps(matching).encode()]
        consumer.poll.return_value = {"partition": [record(value, i + 21) for i, value in enumerate(values)]}
        found = kafka.find(probe, timeout=0.05)
        self.assertEqual(found, {"eventId": probe["eventId"], "partition": 0, "offset": 27})
        consumer.commit.assert_not_called()
        kafka.close()
        consumer.close.assert_called_once()

    def test_unknown_topic_closes_consumer_without_creating_it(self):
        consumer = self.consumer()
        consumer.partitions_for_topic.return_value = None
        with self.assertRaisesRegex(RuntimeError, "must exist"):
            self.kafka(consumer)
        consumer.close.assert_called_once()
        consumer.assign.assert_not_called()

    def test_metadata_failure_also_closes_consumer(self):
        consumer = self.consumer()
        consumer.end_offsets.side_effect = RuntimeError("metadata unavailable")
        with self.assertRaisesRegex(RuntimeError, "unavailable"):
            self.kafka(consumer)
        consumer.close.assert_called_once()

    def test_scan_bounds_do_not_turn_unrelated_records_into_success(self):
        for setting, limit in (("MAX_KAFKA_RECORDS", 1), ("MAX_KAFKA_BYTES", 1)):
            with self.subTest(setting=setting), patch.object(PIPELINE, setting, limit):
                consumer = self.consumer()
                consumer.poll.return_value = {0: [record(b"{}"), record(b"{}", 22)]}
                kafka, _ = self.kafka(consumer)
                with self.assertRaisesRegex(RuntimeError, "exceeded"):
                    kafka.find(PIPELINE.probe_event(), timeout=0.05)
                kafka.close()

    def test_empty_topic_times_out_and_response_sizes_are_bounded(self):
        kafka, _ = self.kafka(self.consumer())
        self.assertIsNone(kafka.find(PIPELINE.probe_event(), timeout=0.005))
        kafka.close()
        with patch.object(PIPELINE, "MAX_RESPONSE_BYTES", 8):
            self.assertEqual(PIPELINE.response_bytes(io.BytesIO(b"12345678")), b"12345678")
            with self.assertRaisesRegex(RuntimeError, "exceeds"):
                PIPELINE.response_bytes(io.BytesIO(b"123456789"))

    def test_nonzero_or_malformed_envelopes_cannot_be_successful_empty_results(self):
        for payload in ({"code": 500, "data": []}, {"code": 0}, {"items": "wrong"}):
            with self.subTest(payload=payload), self.assertRaises(RuntimeError):
                PIPELINE.page_items(payload)

    def test_sql_literals_preserve_opaque_ids_and_escape_quotes_and_backslashes(self):
        self.assertEqual(PIPELINE.sql_literal("a'b\\c"), "'a\\'b\\\\c'")

    def exercise_http_pipeline(self, broken=None):
        state = {"event": None, "requests": []}
        consumer = self.consumer()
        original_kafka = PIPELINE.KafkaProbe
        original_wait = PIPELINE.wait_for
        kafka = original_kafka(Mock(return_value=consumer), lambda topic, number: (topic, number))
        original_find = kafka.find
        kafka.find = lambda event: original_find(event, timeout=0.02)

        def poll(**kwargs):
            event = state["event"]
            if not event or broken == "kafka":
                return {0: [record(b'{"eventId":"unrelated"}')]}
            return {0: [record(json.dumps(event).encode())]}
        consumer.poll.side_effect = poll

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.respond()

            def do_POST(self):
                self.respond()

            def respond(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                state["requests"].append((self.command, self.path, raw, dict(self.headers)))
                event = state["event"]
                result, envelope = {}, True
                if self.path == "/os/":
                    result, envelope = {"cluster_name": "fixture"}, False
                elif self.path.startswith("/detect-web/api/v1/rules/"):
                    result = {"status": "ACTIVE", "enabled": True}
                elif self.path.startswith("/search-config/api/v1/ingest/tasks?"):
                    result = {"items": [{"id": "source/id"}], "total": 1}
                elif self.path == "/search-config/api/v1/ingest/tasks/source%2Fid/test":
                    assert consumer.seek.call_count == 2
                    assert self.headers["Content-Type"] == "application/json"
                    event = json.loads(json.loads(raw)["sample"])
                    state["event"] = {**event, "tenantId": "default", "msg": event["message"]}
                    result = {"ok": broken != "ingest"}
                elif self.path == "/os/socp-events-*/_search":
                    assert json.loads(raw)["query"]["bool"]["filter"][0]["term"]["eventId"] == event["eventId"]
                    candidate = {**event, "tenantId": "other"} if broken == "opensearch" else event
                    result, envelope = {"hits": {"hits": [{"_source": candidate}]}}, False
                elif self.path.startswith("/alert-web/api/alarms/by-event?eventId="):
                    assert event["eventId"] in self.path
                    result = [{"id": "fixture-alarm", "tenantId": "default", "ruleId": "AUTH-PRIVESC",
                               "triggerEventId": "old-event" if broken == "alarm" else event["eventId"],
                               "message": "sudo: ciattacker previous run", "entity": event["host"]}]
                elif self.path == "/ck/":
                    if raw == b"SELECT 1":
                        encoded = b"0" if broken == "preflight" else b"1"
                    else:
                        assert b"tenant_id = 'default'" in raw and b"alarm_id = 'fixture-alarm'" in raw
                        encoded = json.dumps({"tenant_id": "default", "rule_id": "AUTH-PRIVESC",
                                              "alarm_id": "old-alarm" if broken == "clickhouse" else "fixture-alarm"}).encode()
                    self.send_response(200); self.end_headers(); self.wfile.write(encoded); return
                elif self.path == "/report-web/api/v1/reports/daily":
                    result = {"source": "alert-web" if broken == "report" else "clickhouse",
                              "degraded": broken == "report", "total": 99}
                else:
                    self.send_response(404); self.end_headers(); return
                encoded = json.dumps({"code": 0, "data": result} if envelope else result).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(encoded)

            def log_message(self, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:%d" % server.server_port
        try:
            with patch.multiple(PIPELINE, GW=base, OS_URL=base + "/os", CK_URL=base + "/ck", JWT="fixture"), \
                    patch.object(PIPELINE, "KafkaProbe", return_value=kafka), \
                    patch.object(PIPELINE, "wait_for", side_effect=lambda fn, **_: original_wait(fn, timeout=0.025, interval=0.001)), \
                    contextlib.redirect_stdout(self.output):
                status = PIPELINE.main([])
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=2)
        return status, state, consumer

    def test_main_uses_real_http_and_only_the_current_probe_lineage(self):
        status, state, consumer = self.exercise_http_pipeline()
        self.assertEqual(status, 0, self.output.getvalue())
        self.assertEqual(len(PIPELINE.PASS), 9)
        self.assertEqual(PIPELINE.FAIL, [])
        mutations = [path for method, path, _, _ in state["requests"] if method == "POST" and path.startswith("/search-config")]
        self.assertEqual(mutations, ["/search-config/api/v1/ingest/tasks/source%2Fid/test"])
        self.assertFalse(any("_refresh" in path for _, path, _, _ in state["requests"]))
        consumer.close.assert_called_once()

    def test_each_missing_lineage_stage_or_degraded_report_fails_despite_other_traffic(self):
        for stage in ("kafka", "opensearch", "alarm", "clickhouse", "report", "ingest"):
            with self.subTest(stage=stage):
                status, _, consumer = self.exercise_http_pipeline(stage)
                self.assertEqual(status, 1, self.output.getvalue())
                self.assertTrue(PIPELINE.FAIL)
                consumer.close.assert_called_once()

    def test_failed_preflight_stops_before_injection(self):
        status, state, _ = self.exercise_http_pipeline("preflight")
        self.assertEqual(status, 1)
        self.assertIsNone(state["event"])


if __name__ == "__main__":
    unittest.main()
