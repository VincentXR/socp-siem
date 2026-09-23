#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify one identified event across Kafka, OpenSearch, Detection, Alert and ClickHouse.

Run only against a disposable local/CI stack: the task-test endpoint injects an
event and downstream rules can trigger side effects. Existing PIPELINE_GATEWAY,
PIPELINE_OS[_AUTH], PIPELINE_CK[_AUTH], PIPELINE_KAFKA, PIPELINE_JWT/USER/PASS
overrides remain supported. PIPELINE_TENANT defaults to default; PIPELINE_TOPIC
defaults to socp-events. Kafka uses the CI-pinned kafka-python==3.0.10 package.
HTTPS OpenSearch uses the existing local self-signed-certificate exception.
"""

import argparse
import base64
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid
from urllib.parse import quote

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from auth_client import login_token  # noqa: E402

GW = os.environ.get("PIPELINE_GATEWAY", "http://127.0.0.1:18092").rstrip("/")
OS_URL = os.environ.get("PIPELINE_OS", "https://localhost:9200").rstrip("/")
OS_AUTH = os.environ.get("PIPELINE_OS_AUTH", "admin:Socp!Sec2026xK")
CK_URL = os.environ.get("PIPELINE_CK", "http://127.0.0.1:8123").rstrip("/")
CK_AUTH = os.environ.get("PIPELINE_CK_AUTH", "default:socp")
JWT = os.environ.get("PIPELINE_JWT", "")
USER = os.environ.get("PIPELINE_USER", "demo")
PASSWD = os.environ.get("PIPELINE_PASS", "demo123")
TENANT = os.environ.get("PIPELINE_TENANT", "default")
TOPIC = os.environ.get("PIPELINE_TOPIC", "socp-events")
EXPECTED_RULE = "AUTH-PRIVESC"
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
MAX_KAFKA_RECORDS = 10_000
MAX_KAFKA_BYTES = 64 * 1024 * 1024
PASS, FAIL = [], []


def check(name, condition, detail=""):
    (PASS if condition else FAIL).append(name)
    print(("  [PASS] " if condition else "  [FAIL] ") + name
          + (" -> " + str(detail)[:240] if detail else ""), flush=True)
    return bool(condition)


def envelope(payload):
    if isinstance(payload, dict) and "code" in payload:
        if payload.get("code") != 0 or "data" not in payload:
            raise RuntimeError("invalid API envelope: code=%s" % payload.get("code"))
        return payload["data"]
    return payload


def page_items(payload):
    data = envelope(payload)
    if isinstance(data, list):
        return data
    if isinstance(data, dict) and isinstance(data.get("items"), list):
        return data["items"]
    raise RuntimeError("expected a list or paged API response")


def response_bytes(response):
    raw = response.read(MAX_RESPONSE_BYTES + 1)
    if len(raw) > MAX_RESPONSE_BYTES:
        raise RuntimeError("HTTP response exceeds 4 MiB")
    return raw


def api(path, body=None):
    global JWT
    if not JWT:
        JWT = login_token(GW, USER, PASSWD)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(GW + path, data=data, headers={
        "Authorization": "Bearer " + JWT, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=15) as response:
        return envelope(json.loads(response_bytes(response)))


def os_request(path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(OS_URL + path, data=data, headers={
        "Authorization": "Basic " + base64.b64encode(OS_AUTH.encode()).decode(),
        "Content-Type": "application/json"})
    context = None
    if OS_URL.startswith("https://"):
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
    with urllib.request.urlopen(request, timeout=15, context=context) as response:
        return json.loads(response_bytes(response))


def ck_query(sql):
    request = urllib.request.Request(CK_URL + "/", data=sql.encode("utf-8"), headers={
        "Authorization": "Basic " + base64.b64encode(CK_AUTH.encode()).decode(),
        "Content-Type": "text/plain; charset=utf-8"})
    with urllib.request.urlopen(request, timeout=15) as response:
        return response_bytes(response).decode("utf-8").strip()


def wait_for(predicate, timeout=40.0, interval=1.0):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            result = predicate()
            if result:
                return result
        except (OSError, ValueError, RuntimeError) as error:
            last_error = error
        time.sleep(min(interval, max(0, deadline - time.monotonic())))
    if last_error:
        print("  Last probe error: " + str(last_error)[:240])
    return None


def probe_event():
    identity = uuid.uuid4().hex
    return {"eventId": "pipeline-" + identity, "collector": "auth",
            "host": "ci-attack-" + identity, "source": "auth", "severity": "HIGH",
            "message": "sudo: ciattacker-" + identity + " : USER=root ; COMMAND=/bin/sh",
            "src_ip": "198.18.%d.%d" % (int(identity[:2], 16), int(identity[2:4], 16)),
            "user": "ciattacker-" + identity}


def matches_event(event, probe):
    return (isinstance(event, dict) and event.get("eventId") == probe["eventId"]
            and event.get("tenantId") == TENANT and event.get("host") == probe["host"]
            and event.get("source") == probe["source"] and event.get("msg") == probe["message"])


class KafkaProbe:
    """Bounded manual assignment from pre-injection ends; never joins or commits a group."""

    def __init__(self, consumer_factory=None, partition_factory=None):
        if consumer_factory is None:
            from kafka import KafkaConsumer
            from kafka.structs import TopicPartition
            consumer_factory, partition_factory = KafkaConsumer, TopicPartition
        self.consumer = consumer_factory(
            bootstrap_servers=os.environ.get("PIPELINE_KAFKA", "127.0.0.1:9092"),
            group_id=None, enable_auto_commit=False, allow_auto_create_topics=False,
            isolation_level="read_committed", request_timeout_ms=10_000,
            bootstrap_timeout_ms=10_000, max_poll_records=200,
            fetch_max_bytes=8 * 1024 * 1024, max_partition_fetch_bytes=4 * 1024 * 1024)
        try:
            partitions = self.consumer.partitions_for_topic(TOPIC)
            if not partitions or len(partitions) > 256:
                raise RuntimeError("canonical Kafka topic must exist with 1..256 partitions")
            assigned = [partition_factory(TOPIC, number) for number in sorted(partitions)]
            self.consumer.assign(assigned)
            for partition, end in self.consumer.end_offsets(assigned).items():
                self.consumer.seek(partition, end)
        except BaseException:
            self.close()
            raise

    def close(self):
        self.consumer.close()

    def find(self, probe, timeout=60.0):
        deadline = time.monotonic() + timeout
        count = size = 0
        while time.monotonic() < deadline:
            batch = self.consumer.poll(timeout_ms=min(1000, max(1, int((deadline - time.monotonic()) * 1000))))
            for records in batch.values():
                for record in records:
                    count += 1
                    size += len(record.value or b"") + len(record.key or b"")
                    if count > MAX_KAFKA_RECORDS or size > MAX_KAFKA_BYTES:
                        raise RuntimeError("Kafka probe scan exceeded 10000 records or 64 MiB")
                    try:
                        event = json.loads(record.value) if record.value is not None else None
                    except (ValueError, UnicodeError):
                        continue
                    if matches_event(event, probe):
                        return {"eventId": event["eventId"], "partition": record.partition, "offset": record.offset}
        return None


def indexed_event(probe):
    result = os_request("/socp-events-*/_search", {"size": 10, "query": {"bool": {"filter": [
        {"term": {"eventId": probe["eventId"]}}, {"term": {"tenantId": TENANT}}]}}})
    return next((hit for hit in result.get("hits", {}).get("hits", [])
                 if matches_event(hit.get("_source"), probe)), None)


def persisted_alarm(probe):
    alarms = page_items(api("/alert-web/api/alarms/by-event?eventId=" + quote(probe["eventId"], safe="")))
    return next((alarm for alarm in alarms if isinstance(alarm, dict)
                 and alarm.get("triggerEventId") == probe["eventId"]
                 and alarm.get("tenantId") == TENANT and alarm.get("ruleId") == EXPECTED_RULE
                 and isinstance(alarm.get("id"), str) and alarm["id"]), None)


def sql_literal(value):
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def reported_alarm(alarm):
    rows = ck_query("SELECT tenant_id, alarm_id, rule_id FROM alert_agg.alarm_detail WHERE tenant_id = "
                    + sql_literal(TENANT) + " AND alarm_id = " + sql_literal(alarm["id"])
                    + " LIMIT 10 FORMAT JSONEachRow")
    for line in rows.splitlines():
        row = json.loads(line)
        if (isinstance(row, dict) and row.get("tenant_id") == TENANT
                and row.get("alarm_id") == alarm["id"] and row.get("rule_id") == EXPECTED_RULE):
            return row
    return None


def daily_report():
    report = api("/report-web/api/v1/reports/daily")
    return (report if isinstance(report, dict) and report.get("source") == "clickhouse"
            and report.get("degraded") is False and type(report.get("total")) is int
            and report["total"] > 0 else None)


def run():
    probe = probe_event()
    print("Probe event: " + probe["eventId"] + " tenant=" + TENANT)
    os_request("/")
    if not check("ClickHouse authenticated query", ck_query("SELECT 1") == "1"):
        return
    rule = api("/detect-web/api/v1/rules/" + EXPECTED_RULE)
    if not check("Expected detection rule is active", isinstance(rule, dict)
                 and rule.get("status") == "ACTIVE" and rule.get("enabled") is True):
        return
    tasks = page_items(api("/search-config/api/v1/ingest/tasks?page=1&size=100"))
    if not check("Ingestion task available", bool(tasks) and isinstance(tasks[0].get("id"), str)):
        return
    kafka = KafkaProbe()
    try:
        # The cursor must be captured BEFORE the only mutation in this verifier.
        result = api("/search-config/api/v1/ingest/tasks/" + quote(tasks[0]["id"], safe="") + "/test",
                     {"sample": json.dumps(probe)})
        if not check("Probe accepted by ingestion", isinstance(result, dict) and result.get("ok") is True):
            return
        observed = kafka.find(probe)
        check("Exact canonical event in Kafka", observed is not None, observed)
    finally:
        kafka.close()
    indexed = wait_for(lambda: indexed_event(probe))
    check("Exact raw event in OpenSearch", indexed is not None, probe["eventId"])
    alarm = wait_for(lambda: persisted_alarm(probe), timeout=60)
    check("Expected rule materialized this event's alarm", alarm is not None, alarm.get("id") if alarm else "")
    reported = wait_for(lambda: reported_alarm(alarm)) if alarm else None
    check("Same tenant/alarm ID in ClickHouse", reported is not None, reported)
    report = wait_for(daily_report)
    check("Daily report uses available ClickHouse data", report is not None)


def main(argv=None):
    argparse.ArgumentParser(description=__doc__).parse_args(argv)
    PASS.clear(); FAIL.clear()
    print("=== SOCP identified-event pipeline verification ===")
    try:
        run()
    except Exception as error:
        check("Pipeline verification completed", False, "%s: %s" % (type(error).__name__, error))
    print("Pipeline checks: %d passed, %d failed" % (len(PASS), len(FAIL)))
    return 1 if FAIL or not PASS else 0


if __name__ == "__main__":
    raise SystemExit(main())
