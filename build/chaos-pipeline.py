#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Run a small, repeatable failure matrix against a running SOCP stack.

This script is deliberately conservative: it only stops named SOCP services
through ``build/run-all.sh`` and uses unique event IDs for every run. It does
not claim exactly-once delivery; it checks the concrete invariants implemented
by the current pipeline (Kafka backlog recovery, multi-instance partition
ownership, durable Alert Web delivery, and source-alert idempotency).

Examples (Linux/macOS/WSL):
  python build/chaos-pipeline.py --scenario all --output .cache/chaos.json
  python build/chaos-pipeline.py --scenario duplicate_delivery
  python build/chaos-pipeline.py --scenario alert_web_restart
  DETECTION_INSTANCE_URLS=http://127.0.0.1:18082,http://127.0.0.1:28082,http://127.0.0.1:38082 \
    python build/chaos-pipeline.py --scenario multi_instance
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

BUILD = Path(__file__).resolve().parent
REPO = BUILD.parent
sys.path.insert(0, str(BUILD))

from ports import GATEWAY_URL, health_url, port_of  # noqa: E402
from auth_client import login_token  # noqa: E402


BOOTSTRAP = os.environ.get("PIPELINE_KAFKA", "127.0.0.1:9092")
TOPIC = os.environ.get("RECOVERY_TOPIC", "socp-events")
GROUP = os.environ.get("RECOVERY_GROUP", os.environ.get("SOCP_KAFKA_GROUP_ID", "socp-detect"))
USER = os.environ.get("DEMO_USER", "demo")
PASSWORD = os.environ.get("DEMO_PASS", "demo123")
VECTOR_TOKEN = os.environ.get("SOCP_VECTOR_TOKEN", "dev-vector-token")
RUNNER = os.environ.get("SOCP_BASH", "bash")
DEFAULT_DATASET = BUILD / "datasets" / "chaos-v1.json"
DATASET_SPEC = {}
RUN_NAMESPACE = ""

def request(url, method="GET", body=None, headers=None, timeout=20):
    data = None if body is None else (body if isinstance(body, bytes) else body.encode())
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            try:
                parsed = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                parsed = {"raw": raw}
            return response.status, parsed
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"raw": raw}
        return error.code, parsed
    except Exception as error:
        return 0, {"error": str(error)}


def load_dataset(path):
    with Path(path).open(encoding="utf-8") as handle:
        dataset = json.load(handle)
    if not dataset.get("version") or not isinstance(dataset.get("seed"), int):
        raise RuntimeError(f"invalid chaos dataset metadata: {path}")
    return dataset


def run_token(scenario):
    material = f"{DATASET_SPEC.get('seed')}:{RUN_NAMESPACE}:{scenario}"
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:12]


def start_auto_detection_cluster():
    raw_ports = os.environ.get(
        "SOCP_DETECT_CLUSTER_PORTS",
        f"{port_of('detect-web')},28082,38082")
    ports = [int(item.strip()) for item in raw_ports.split(",") if item.strip()]
    if len(ports) != 3:
        raise RuntimeError("SOCP_DETECT_CLUSTER_PORTS must contain exactly three ports")
    script = BUILD / "detection-cluster.sh"
    command = [RUNNER, str(script), "start"]
    result = subprocess.run(command, cwd=REPO, capture_output=True, text=True, timeout=180, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"automatic three-instance Detection start failed: {result.stderr[-1000:]}")
    os.environ["DETECTION_INSTANCE_URLS"] = ",".join(f"http://127.0.0.1:{port}" for port in ports)
    return ports


def stop_auto_detection_cluster():
    script = BUILD / "detection-cluster.sh"
    subprocess.run([RUNNER, str(script), "stop"], cwd=REPO,
                   capture_output=True, text=True, timeout=60, check=False)


def unwrap(body):
    return body.get("data") if isinstance(body, dict) and "data" in body else body


def login():
    return login_token(GATEWAY_URL, USER, PASSWORD)


def auth_headers(token):
    return {"Authorization": "Bearer " + token}


def wait_for(predicate, timeout=120, interval=2):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = predicate()
        if last:
            return last
        time.sleep(interval)
    return last


def service_up(service, token=None):
    status, _ = request(health_url(service),
                        headers=auth_headers(token) if token else {}, timeout=4)
    return status == 200


def direct_instance_up(base_url, token=None):
    status, _ = request(base_url.rstrip("/") + "/detect-web/actuator/health",
                        headers=auth_headers(token) if token else {}, timeout=4)
    return status == 200


def direct_instance_stats(base_url, token=None):
    status, body = request(base_url.rstrip("/") + "/detect-web/api/v1/stats",
                           headers=auth_headers(token) if token else {}, timeout=5)
    data = unwrap(body)
    return data if status == 200 and isinstance(data, dict) else None


def control(action, service):
    # The repository's shell runner is the canonical Unix/WSL path.  Native
    # Windows installations often have no WSL bash, so keep the same explicit
    # service semantics available through PowerShell: only the named service's
    # port is stopped and only its built JAR is started.
    if os.name == "nt" and "SOCP_BASH" not in os.environ:
        port = port_of(service)
        jar = REPO / "services" / service / "target" / f"{service}-1.0.0-SNAPSHOT.jar"
        if action == "stop-service":
            script = (
                f"$c=Get-NetTCPConnection -State Listen -LocalPort {port} "
                f"-ErrorAction SilentlyContinue; "
                f"$c | Select-Object -ExpandProperty OwningProcess -Unique | "
                f"ForEach-Object {{ Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }}"
            )
        elif action == "start-service":
            if not jar.is_file():
                raise RuntimeError(f"missing service JAR: {jar}")
            log_out = REPO / ".cache" / f"{service}-chaos.out.log"
            log_err = REPO / ".cache" / f"{service}-chaos.err.log"
            env = os.environ.copy()
            env.setdefault("SOCP_JWT_SECRET",
                           "socp-demo-jwt-secret-0123456789abcdef0123456789abcdef")
            env.setdefault("SOCP_LOGIN_SECRET", env["SOCP_JWT_SECRET"])
            log_out.parent.mkdir(parents=True, exist_ok=True)
            with log_out.open("ab") as stdout, log_err.open("ab") as stderr:
                flags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) \
                        | getattr(subprocess, "DETACHED_PROCESS", 0)
                arguments = ["java", "-Xms32m", "-Xmx256m", "-jar", str(jar),
                             f"--server.port={port}"]
                profile = os.environ.get("SOCP_DETECT_PROFILE")
                if service == "detect-web" and profile:
                    arguments.insert(-1, f"--spring.profiles.active={profile}")
                subprocess.Popen(
                    arguments,
                    cwd=REPO, env=env, stdout=stdout, stderr=stderr,
                    creationflags=flags,
                    close_fds=True)
            return
        else:
            raise RuntimeError(f"unsupported Windows control action: {action}")
        result = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
            cwd=REPO, capture_output=True, text=True, timeout=60, check=False)
        if result.returncode != 0:
            raise RuntimeError(f"{action} {service} failed: {result.stderr[-500:]}")
        return

    result = subprocess.run(
        [RUNNER, str(REPO / "build" / "run-all.sh"), action, service],
        cwd=REPO, capture_output=True, text=True, timeout=60, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"{action} {service} failed: {result.stderr[-500:]}")


CONTAINER_IMAGES = {
    "socp-postgres": os.environ.get("SOCP_POSTGRES_IMAGE", "postgres:18"),
    "socp-opensearch": os.environ.get(
        "SOCP_OPENSEARCH_IMAGE", "opensearchproject/opensearch:2.19.6"),
}


def resolve_container(container):
    """Resolve compose names and GitHub service-container IDs alike."""
    env_name = "SOCP_" + container.removeprefix("socp-").replace("-", "_").upper() + "_CONTAINER"
    configured = os.environ.get(env_name)
    if configured:
        return configured
    inspected = subprocess.run(
        ["docker", "inspect", container], cwd=REPO,
        capture_output=True, text=True, timeout=15, check=False)
    if inspected.returncode == 0:
        return container
    image = CONTAINER_IMAGES.get(container)
    if image:
        discovered = subprocess.run(
            ["docker", "ps", "-aq", "--filter", f"ancestor={image}",
             "--format", "{{{{.Names}}}}"], cwd=REPO,
            capture_output=True, text=True, timeout=15, check=False)
        name = next((line.strip() for line in discovered.stdout.splitlines()
                     if line.strip()), None)
        if name:
            return name
    return container


def docker_container(action, container):
    target = resolve_container(container)
    result = subprocess.run(
        ["docker", action, target], cwd=REPO,
        capture_output=True, text=True, timeout=90, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"docker {action} {target} failed: {result.stderr[-500:]}")


def container_running(container):
    container = resolve_container(container)
    result = subprocess.run(
        ["docker", "inspect", "-f", "{{.State.Running}}", container],
        cwd=REPO, capture_output=True, text=True, timeout=15, check=False)
    return result.returncode == 0 and result.stdout.strip().lower() == "true"


def container_healthy(container):
    container = resolve_container(container)
    result = subprocess.run(
        ["docker", "inspect", "-f",
         "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}",
         container],
        cwd=REPO, capture_output=True, text=True, timeout=15, check=False)
    return result.returncode == 0 and result.stdout.strip().lower() in ("healthy", "running")


def psql_scalar(database, sql):
    container = resolve_container("socp-postgres")
    result = subprocess.run(
        ["docker", "exec", container, "psql", "-U", "socp", "-d", database,
         "-tAc", sql],
        cwd=REPO, capture_output=True, text=True, timeout=30, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"PostgreSQL query failed: {result.stderr[-500:]}")
    return result.stdout.strip()


def clickhouse_scalar(sql):
    base = os.environ.get("PIPELINE_CK", os.environ.get("SOCP_CK_URL", "http://localhost:8123"))
    auth = os.environ.get("PIPELINE_CK_AUTH", "default:socp")
    headers = {}
    if ":" in auth:
        user, password = auth.split(":", 1)
        token = (user + ":" + password).encode("utf-8")
        headers["Authorization"] = "Basic " + __import__("base64").b64encode(token).decode()
    status, body = request(base.rstrip("/") + "/?query=" + urllib.parse.quote(sql),
                           headers=headers, timeout=10)
    if status != 200:
        return None
    if isinstance(body, dict):
        return body.get("raw")
    return str(body).strip()


def delivery_evidence(source_alert_ids):
    """Return durable fan-out counts for a set of source alert IDs."""
    if not source_alert_ids:
        return {"rows": 0, "duplicates": 0, "pendingOrProcessing": 0,
                "undelivered": 0, "distinctRows": 0}
    quoted = ",".join("'" + value.replace("'", "''") + "'" for value in source_alert_ids)
    join = "alarm_delivery d join t_alarm a on a.tenant_id=d.tenant_id and a.id=d.alarm_id"
    where = f"a.source_alert_id in ({quoted})"
    rows = int(psql_scalar("alert", f"select count(*) from {join} where {where}") or 0)
    distinct = int(psql_scalar("alert", f"select count(distinct d.tenant_id || ':' || d.alarm_id || ':' || d.destination) from {join} where {where}") or 0)
    pending = int(psql_scalar("alert", f"select count(*) from {join} where {where} and d.status in ('PENDING','PROCESSING')") or 0)
    undelivered = int(psql_scalar("alert", f"select count(*) from {join} where {where} and d.status <> 'DELIVERED'") or 0)
    return {"rows": rows, "duplicates": max(0, rows - distinct),
            "pendingOrProcessing": pending, "undelivered": undelivered,
            "distinctRows": distinct}


def publish_detection_event(event):
    """Publish a canonical event without depending on the PostgreSQL-backed ingress.

    Dependency-outage scenarios must inject work on the upstream durable
    boundary they are trying to test.  Going through Search Config while the
    shared PostgreSQL service is down only proves that ingress rejects the
    request; it never creates Kafka backlog for Detection to recover.
    """
    try:
        from kafka import KafkaProducer
    except ImportError as error:
        raise RuntimeError("kafka-python is required for direct chaos publication") from error

    tenant = str(event.get("tenantId") or "default")
    source = str(event.get("source") or "unknown")
    host = str(event.get("host") or "unknown")
    fields = {"tenant_id": tenant, "host": host,
              "detection_routing_field": "host",
              "detection_routing_value": host}
    fields.update({key: str(value) for key, value in event.items()
                   if key not in {"eventId", "tenantId", "source", "host",
                                  "severity", "message", "msg", "timestamp"}
                   and value is not None})
    payload = {
        "eventId": str(event["eventId"]),
        "tenantId": tenant,
        "source": source,
        "host": host,
        "severity": str(event.get("severity") or "INFO").upper(),
        "msg": str(event.get("msg") or event.get("message") or ""),
        "timestamp": str(event.get("timestamp") or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
        "fields": fields,
    }
    routing_key = f"{tenant}|host|{host}"
    producer = KafkaProducer(bootstrap_servers=BOOTSTRAP, acks="all", retries=3)
    try:
        metadata = producer.send(
            TOPIC, key=routing_key.encode("utf-8"),
            value=json.dumps(payload, separators=(",", ":")).encode("utf-8")).get(timeout=30)
        producer.flush(timeout=30)
        return {"published": 1, "topic": metadata.topic,
                "partition": metadata.partition, "offset": metadata.offset,
                "routingKey": routing_key}
    finally:
        producer.close(timeout=10)


def kafka_cli_snapshot():
    """Read offsets with the broker image's own CLI when Compose is available.

    This keeps the chaos oracle aligned with the running Kafka distribution and
    avoids coupling scheduled evidence to a third-party client's admin-protocol
    implementation. Non-Compose installations transparently use kafka-python.
    """
    target = resolve_container("socp-kafka")
    inspected = subprocess.run(
        ["docker", "inspect", target], cwd=REPO,
        capture_output=True, text=True, timeout=15, check=False)
    if inspected.returncode != 0:
        return None
    result = subprocess.run(
        ["docker", "exec", target, "/opt/kafka/bin/kafka-consumer-groups.sh",
         "--bootstrap-server", "localhost:9092", "--group", GROUP, "--describe"],
        cwd=REPO, capture_output=True, text=True, timeout=30, check=False)
    if result.returncode != 0:
        return None

    partitions = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) < 6 or fields[0] != GROUP or fields[1] != TOPIC:
            continue
        try:
            partition = int(fields[2])
            committed = int(fields[3]) if fields[3] != "-" else 0
            end = int(fields[4])
            lag = int(fields[5]) if fields[5] != "-" else max(0, end - committed)
        except ValueError:
            continue
        partitions.append({"partition": partition, "end": end,
                           "committed": committed, "lag": max(0, lag)})
    if not partitions:
        return None
    partitions.sort(key=lambda item: item["partition"])
    return {"end": sum(item["end"] for item in partitions),
            "committed": sum(item["committed"] for item in partitions),
            "lag": sum(item["lag"] for item in partitions),
            "partitions": len(partitions),
            "perPartition": partitions,
            "source": "broker-cli"}


def kafka_snapshot():
    cli_snapshot = kafka_cli_snapshot()
    if cli_snapshot is not None:
        return cli_snapshot
    try:
        from kafka import KafkaConsumer
        from kafka.admin import KafkaAdminClient
        from kafka.structs import TopicPartition
    except ImportError as error:
        raise RuntimeError("kafka-python is required for chaos checks") from error

    # Do not join the production consumer group just to inspect its offsets.
    # A diagnostic consumer joining GROUP triggers a rebalance and can revoke
    # live Detection partitions while a benchmark or chaos scenario is active.
    consumer = KafkaConsumer(
        bootstrap_servers=BOOTSTRAP,
        group_id=None,
        enable_auto_commit=False,
        request_timeout_ms=5000,
    )
    admin = None
    try:
        admin = KafkaAdminClient(bootstrap_servers=BOOTSTRAP, client_id="socp-chaos-offset-inspector")
        partitions = consumer.partitions_for_topic(TOPIC)
        if not partitions:
            raise RuntimeError(f"Kafka topic {TOPIC} has no partitions")
        tps = [TopicPartition(TOPIC, p) for p in sorted(partitions)]
        consumer.assign(tps)
        ends = consumer.end_offsets(tps)
        committed = admin.list_group_offsets({GROUP: tps}).get(GROUP, {})
        end_total = sum(ends.values())
        def committed_value(value):
            if value is None:
                return 0
            if isinstance(value, int):
                return value
            return int(getattr(value, "offset", 0))

        per_partition = []
        committed_total = 0
        for tp in tps:
            current = committed_value(committed.get(tp))
            end = int(ends.get(tp, 0))
            committed_total += current
            per_partition.append({"partition": tp.partition,
                                  "end": end,
                                  "committed": current,
                                  "lag": max(0, end - current)})
        return {"end": end_total, "committed": committed_total,
                "lag": max(0, end_total - committed_total),
                "partitions": len(tps),
                "perPartition": per_partition,
                "source": "kafka-python"}
    finally:
        if admin is not None:
            admin.close()
        consumer.close()


def drained_kafka_snapshot(timeout=60, interval=1):
    """Return a zero-lag baseline, waiting for the previous workload to drain.

    Restart and dependency-outage scenarios inject a new workload and compare
    offsets against the baseline.  Sampling only once made an otherwise valid
    run fail when the preceding verification was still committing its final
    records.  Keep the diagnostic consumer out of the production group and
    wait only for the observable lag invariant.
    """
    last = kafka_snapshot()
    if last is None or last["lag"] == 0:
        return last

    def probe():
        nonlocal last
        snapshot = kafka_snapshot()
        if snapshot is not None:
            last = snapshot
        return snapshot if snapshot and snapshot["lag"] == 0 else None

    return wait_for(probe, timeout=timeout, interval=interval) or last


def alert_total(token):
    status, body = request(
        GATEWAY_URL + "/alert-web/api/alarms?page=1&size=1",
        headers=auth_headers(token))
    data = unwrap(body)
    if status != 200 or not isinstance(data, dict):
        return None
    try:
        return int(data.get("total", 0))
    except (TypeError, ValueError):
        return None


def list_alerts(token):
    status, body = request(
        GATEWAY_URL + "/alert-web/api/alarms?page=1&size=500",
        headers=auth_headers(token))
    data = unwrap(body)
    if status != 200:
        return []
    if isinstance(data, dict):
        return data.get("items", [])
    return data if isinstance(data, list) else []


def java_name_uuid(value):
    """Match java.util.UUID.nameUUIDFromBytes used by Alert.stableId."""
    digest = bytearray(hashlib.md5(value.encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def expected_alert_id(rule_id, entity, event_ids, tenant="default"):
    """Match threshold Alert.stableId: tenant, rule, entity, then sorted evidence IDs."""
    return java_name_uuid("|".join([tenant, rule_id, entity, *sorted(event_ids)]))


def ingest(token, events):
    payload = "\n".join(json.dumps(event) for event in events) + "\n"
    collector_token = os.environ.get("PIPELINE_COLLECTOR_TOKEN", "").strip()
    collector_id = os.environ.get("PIPELINE_COLLECTOR_ID", "chaos-pipeline").strip()
    if collector_token:
        # Collector credentials are data-plane identities and are sent to the
        # search service directly.  The north-bound gateway intentionally
        # accepts user JWTs only, while the service boundary validates the
        # collector/tenant binding.
        ingest_url = os.environ.get(
            "PIPELINE_INGEST_URL",
            f"http://127.0.0.1:{port_of('search-config')}/search-config/api/v1/ingest")
        headers = {
            "Authorization": "Bearer " + collector_token,
            "X-SOCP-Collector": collector_id,
            "Content-Type": "application/x-ndjson",
        }
    else:
        legacy_token = os.environ.get("SOCP_INGEST_TOKEN", VECTOR_TOKEN).strip()
        ingest_url = os.environ.get(
            "PIPELINE_INGEST_URL",
            f"http://127.0.0.1:{port_of('search-config')}/search-config/api/v1/ingest")
        headers = {
            "Authorization": "Bearer " + legacy_token,
            "X-SOCP-Collector": collector_id,
            "Content-Type": "application/x-ndjson",
        }
    status, body = request(
        ingest_url, "POST", payload, headers, timeout=30)
    if status != 200:
        raise RuntimeError(f"ingest failed HTTP {status}: {body}")
    return body


def scenario_detection_restart(token, count):
    baseline = drained_kafka_snapshot()
    if baseline is None:
        raise RuntimeError("Kafka snapshot unavailable; check kafka-python and the broker")
    if baseline["lag"] != 0:
        raise RuntimeError(f"detection restart scenario requires a drained baseline, got {baseline}")
    stopped = False
    try:
        control("stop-service", "detect-web")
        stopped = wait_for(lambda: not service_up("detect-web", token), timeout=30)
        if not stopped:
            raise RuntimeError("detect-web did not stop")
        run_id = run_token("detect-restart")
        events = [{
            "eventId": f"chaos-restart-{run_id}-{i}",
            "source": "auth",
            "host": f"chaos-restart-{run_id}",
            "severity": "WARN",
            "message": f"Failed password for invalid user root from 198.51.100.77 port {52000 + i} ssh2",
            "src_ip": "198.51.100.77",
            "user": "root",
        } for i in range(count)]
        accepted = ingest(token, events)
        accepted_count = int(accepted.get("accepted", count)) if isinstance(accepted, dict) else count

        def queued_snapshot():
            snapshot = kafka_snapshot()
            return snapshot if snapshot and snapshot["end"] >= baseline["end"] + accepted_count else None

        queued = wait_for(queued_snapshot, timeout=30, interval=1) or kafka_snapshot()
        if queued is None:
            raise RuntimeError("Kafka backlog snapshot unavailable while detect-web was stopped")
        control("start-service", "detect-web")
        stopped = False
        recovered = wait_for(lambda: kafka_snapshot() if service_up("detect-web", token) else None,
                             timeout=120, interval=3)
        if recovered is None:
            raise RuntimeError("detect-web did not recover")

        def drained_snapshot():
            snapshot = kafka_snapshot()
            return snapshot if snapshot and snapshot["lag"] == 0 else None

        recovered = wait_for(drained_snapshot, timeout=120, interval=3) or kafka_snapshot()
        if recovered is None:
            raise RuntimeError("Kafka snapshot unavailable while waiting for Detection recovery")
        stats = direct_instance_stats(GATEWAY_URL, token)
        return {
            "accepted": accepted,
            "acceptedCount": accepted_count,
            "baseline": baseline,
            "whileStopped": queued,
            "afterRecovery": recovered,
            "pendingEventsAfterRecovery": (stats or {}).get("pendingEvents"),
            "pass": queued["end"] >= baseline["end"] + accepted_count and recovered["lag"] == 0
                    and (stats or {}).get("pendingEvents") == 0,
        }
    finally:
        if stopped and not service_up("detect-web", token):
            control("start-service", "detect-web")


def scenario_duplicate_delivery(token):
    run_id = run_token("duplicate-delivery")
    event_id = f"chaos-duplicate-{run_id}"
    event = {
        "eventId": event_id,
        "source": "auth",
        "host": f"chaos-duplicate-{run_id}",
        "severity": "HIGH",
        "message": "sudo: chaos duplicate-delivery probe",
    }
    before = alert_total(token) or 0
    accepted = ingest(token, [event, event])
    def recovered_total():
        value = alert_total(token)
        return value if value is not None and value >= before + 1 else None

    after = wait_for(recovered_total, timeout=60, interval=1)
    # Pattern alert ids are derived from rule/entity/evidence, not event id;
    # sourceAlertId is therefore checked by counting the matching host/rule.
    all_alerts = list_alerts(token)
    matching = [a for a in all_alerts
                if a.get("ruleId") == "AUTH-PRIVESC" and a.get("entity") == event["host"]]
    return {
        "accepted": accepted,
        "alertCountBefore": before,
        "alertCountAfter": after,
        "matchingAlerts": len(matching),
        "pass": after is not None and len(matching) == 1,
        "eventId": event_id,
        "sourceAlertIds": [a.get("sourceAlertId") for a in matching],
    }


def scenario_alert_web_restart(token):
    """Verify Detection's durable outbox survives an Alert Web outage."""
    if not service_up("detect-web", token):
        raise RuntimeError("detect-web must be healthy before alert_web_restart")
    run_id = run_token("alert-web-restart")
    host = f"chaos-alert-web-{run_id}"
    event = {
        "eventId": f"chaos-alert-web-{run_id}",
        "source": "auth",
        "host": host,
        "severity": "CRITICAL",
        "message": "sudo: alert-web outage delivery probe",
    }
    before = alert_total(token) or 0
    stopped = False
    try:
        control("stop-service", "alert-web")
        stopped = wait_for(lambda: not service_up("alert-web", token), timeout=30, interval=1)
        if not stopped:
            raise RuntimeError("alert-web did not stop")
        accepted = ingest(token, [event])
        time.sleep(5)
        while_down = alert_total(token)
        control("start-service", "alert-web")
        stopped = False

        def recovered_total():
            value = alert_total(token)
            return value if value is not None and value >= before + 1 else None

        recovered = wait_for(recovered_total, timeout=180, interval=2)
        matching = [item for item in list_alerts(token)
                    if item.get("entity") == host and item.get("ruleId") == "AUTH-PRIVESC"]
        return {
            "accepted": accepted,
            "before": before,
            "whileAlertWebDown": while_down,
            "afterRecovery": recovered,
            "matchingAlerts": len(matching),
            "sourceAlertIds": [item.get("sourceAlertId") for item in matching],
            "pass": recovered is not None and len(matching) == 1,
        }
    finally:
        if stopped and not service_up("alert-web", token):
            control("start-service", "alert-web")


def scenario_postgres_outage(token):
    """Prove PostgreSQL failure retains Kafka backlog and recovers exactly once."""
    baseline = drained_kafka_snapshot()
    if not baseline or baseline["lag"] != 0:
        raise RuntimeError(f"postgres_outage requires a drained baseline, got {baseline}")
    run_id = run_token("postgres-outage")
    host = f"chaos-pg-{run_id}"
    event = {
        "eventId": f"chaos-pg-{run_id}",
        "source": "auth",
        "host": host,
        "severity": "CRITICAL",
        "message": "sudo: postgres outage recovery probe",
    }
    before = alert_total(token) or 0
    stopped = False
    try:
        docker_container("stop", "socp-postgres")
        stopped = True
        wait_for(lambda: not container_running("socp-postgres"), timeout=30, interval=1)
        # PostgreSQL is shared by ingress and Detection in this deployment.
        # Publish on Kafka directly so this scenario actually exercises
        # Detection's durable backlog and database recovery boundary.
        accepted = publish_detection_event(event)

        def queued_snapshot():
            snapshot = kafka_snapshot()
            return snapshot if snapshot and snapshot["end"] >= baseline["end"] + 1 else None

        queued = wait_for(queued_snapshot, timeout=30, interval=1) or kafka_snapshot()
        docker_container("start", "socp-postgres")
        stopped = False

        recovered_services = wait_for(
            lambda: all(direct_instance_up(url, token) for url in detection_urls())
                    and service_up("alert-web", token),
            timeout=180, interval=3)

        def matching():
            values = [item for item in list_alerts(token)
                      if item.get("entity") == host and item.get("ruleId") == "AUTH-PRIVESC"]
            return values if len(values) == 1 else None

        alarms = wait_for(matching, timeout=240, interval=2) or []
        drained = wait_for(
            lambda: (snapshot if (snapshot := kafka_snapshot())["lag"] == 0 else None),
            timeout=240, interval=3) or kafka_snapshot()
        stats = [direct_instance_stats(url, token) for url in detection_urls()]
        pending = [item.get("pendingEvents") for item in stats if isinstance(item, dict)]
        return {
            "accepted": accepted,
            "baseline": baseline,
            "whilePostgresDown": queued,
            "afterRecovery": drained,
            "servicesRecovered": bool(recovered_services),
            "matchingAlerts": len(alarms),
            "pendingEventsAfterRecovery": pending,
            "alertCountBefore": before,
            "alertCountAfter": alert_total(token),
            "pass": bool(recovered_services) and len(alarms) == 1
                    and drained["lag"] == 0 and pending and all(value == 0 for value in pending),
        }
    finally:
        if stopped or not container_running("socp-postgres"):
            docker_container("start", "socp-postgres")


def scenario_opensearch_outage(token):
    """Prove Detection is independent from OpenSearch and indexing recovers."""
    run_id = run_token("opensearch-outage")
    alert_host = f"chaos-os-alert-{run_id}"
    recovery_host = f"chaos-os-recovery-{run_id}"
    stopped = False
    try:
        docker_container("stop", "socp-opensearch")
        stopped = True
        wait_for(lambda: not container_running("socp-opensearch"), timeout=30, interval=1)
        accepted = ingest(token, [{
            "eventId": f"chaos-os-alert-{run_id}",
            "source": "auth",
            "host": alert_host,
            "severity": "HIGH",
            "message": "sudo: opensearch outage detection probe",
        }])

        def matching():
            values = [item for item in list_alerts(token)
                      if item.get("entity") == alert_host and item.get("ruleId") == "AUTH-PRIVESC"]
            return values if len(values) == 1 else None

        alarms = wait_for(matching, timeout=180, interval=2) or []
        search_alive = service_up("search-config", token)
        docker_container("start", "socp-opensearch")
        stopped = False
        # The local OpenSearch endpoint can require TLS/basic authentication;
        # Docker health is the transport-readiness check, while the indexed
        # recovery event below is the functional oracle.
        os_ready = wait_for(
            lambda: container_healthy("socp-opensearch"), timeout=120, interval=3)
        recovery = ingest(token, [{
            "eventId": f"chaos-os-recovery-{run_id}",
            "source": "system",
            "host": recovery_host,
            "severity": "INFO",
            "message": "opensearch recovery probe",
        }])

        def searchable():
            query = urllib.parse.quote(f"host={recovery_host}", safe="")
            status, body = request(
                GATEWAY_URL + "/search-config/api/v1/search?q=" + query,
                headers=auth_headers(token), timeout=10)
            data = unwrap(body)
            events = data.get("events", []) if isinstance(data, dict) else []
            return any(item.get("host") == recovery_host for item in events)

        indexed_after_recovery = wait_for(searchable, timeout=120, interval=3)
        return {
            "acceptedWhileDown": accepted,
            "matchingAlertsWhileDown": len(alarms),
            "searchConfigAliveWhileDown": search_alive,
            "openSearchRecovered": bool(os_ready),
            "acceptedAfterRecovery": recovery,
            "recoveryEventIndexed": bool(indexed_after_recovery),
            "pass": len(alarms) == 1 and search_alive and bool(os_ready)
                    and bool(indexed_after_recovery),
        }
    finally:
        if stopped or not container_running("socp-opensearch"):
            docker_container("start", "socp-opensearch")


def scenario_detection_outbox_replay(token):
    """Simulate publish success followed by a crash before the durable ACK."""
    run_id = run_token("detection-outbox-replay")
    host = f"chaos-outbox-{run_id}"
    ingest_result = ingest(token, [{
        "eventId": f"chaos-outbox-{run_id}",
        "source": "auth",
        "host": host,
        "severity": "CRITICAL",
        "message": "sudo: detection outbox replay probe",
    }])

    def matching():
        values = [item for item in list_alerts(token)
                  if item.get("entity") == host and item.get("ruleId") == "AUTH-PRIVESC"]
        return values if len(values) == 1 else None

    initial = wait_for(matching, timeout=180, interval=2) or []
    if len(initial) != 1 or not initial[0].get("sourceAlertId"):
        raise RuntimeError("outbox replay probe did not create its initial durable alert")
    source_alert_id = initial[0]["sourceAlertId"]
    changed = psql_scalar(
        "detect",
        "with replayed as (update t_detection_alert_outbox set status='PENDING', attempts=0, "
        "next_attempt_at=now(), delivered_at=null, published_at=null, last_error=null "
        f"where alert_id='{source_alert_id}' returning alert_id) select alert_id from replayed")
    if changed != source_alert_id:
        raise RuntimeError(f"unable to rewind Detection outbox row {source_alert_id}: {changed}")

    published = wait_for(
        lambda: psql_scalar(
            "detect",
            f"select status from t_detection_alert_outbox where alert_id='{source_alert_id}'") == "PUBLISHED",
        timeout=120, interval=2)
    replayed = [item for item in list_alerts(token)
                if item.get("sourceAlertId") == source_alert_id]
    return {
        "accepted": ingest_result,
        "sourceAlertId": source_alert_id,
        "rewoundRow": changed,
        "statusAfterReplay": psql_scalar(
            "detect", f"select status from t_detection_alert_outbox where alert_id='{source_alert_id}'"),
        "matchingAlerts": len(replayed),
        "pass": bool(published) and len(replayed) == 1,
    }


def detection_urls():
    raw_urls = os.environ.get("DETECTION_INSTANCE_URLS", "")
    values = [item.strip().rstrip("/") for item in raw_urls.split(",") if item.strip()]
    return values or [GATEWAY_URL]


def scenario_multi_instance(token, count, rebalance_cycles=1):
    """Prove stable entity routing, assignment ownership, rebalance, and recovery.

    The first URL is the instance controlled by run-all.sh or the fixed
    detection-cluster launcher. All three URLs use the same Kafka group and
    PostgreSQL profile.
    """
    raw_urls = os.environ.get("DETECTION_INSTANCE_URLS", "")
    urls = [item.strip().rstrip("/") for item in raw_urls.split(",") if item.strip()]
    if len(urls) < 3:
        raise RuntimeError("multi_instance requires exactly three Detection instance URLs")

    baseline = kafka_snapshot()
    if not baseline or baseline["partitions"] < len(urls):
        raise RuntimeError(
            f"multi_instance requires at least {len(urls)} Kafka partitions; got {baseline}")
    if not all(wait_for(lambda url=url: direct_instance_up(url, token), timeout=30, interval=1)
               for url in urls):
        raise RuntimeError(f"not all Detection instances are healthy: {urls}")

    def assignments():
        values = [direct_instance_stats(url, token) for url in urls]
        if any(not isinstance(item, dict) for item in values):
            return None
        partitions = [set(item.get("assignedPartitions", [])) for item in values]
        if any(not item for item in partitions):
            return None
        if set().union(*partitions) != set(range(baseline["partitions"])):
            return None
        if sum(len(item) for item in partitions) != len(set().union(*partitions)):
            return None
        return {"instances": urls, "assignedPartitions": [sorted(item) for item in partitions]}

    initial = wait_for(assignments, timeout=90, interval=2)
    if initial is None:
        raise RuntimeError("Detection instances did not obtain disjoint full partition ownership")

    run_id = run_token("multi-instance")
    dataset = DATASET_SPEC.get("multiInstance", {})
    events_per_alert = int(dataset.get("eventsPerAlert", 5))
    divisor = max(1, int(dataset.get("groupsPerBatchDivisor", events_per_alert)))
    group_count = max(1, count // divisor)
    digest = hashlib.sha256(
        f"{DATASET_SPEC['seed']}:{RUN_NAMESPACE}:multi-instance".encode("utf-8")
    ).digest()
    run_octets = (digest[0], digest[1])

    def threshold_batch(prefix, ip_start):
        events = []
        expected = []
        for group in range(group_count):
            # A unique entity per run prevents a previous run's durable
            # suppression/window state from hiding the current oracle alerts.
            # ip_start only separates the pre/post-rebalance batches.
            last_octet = 1 + ((ip_start + group) % 253)
            src_ip = f"{dataset.get('sourceIpPrefix', '10.240')}.{run_octets[1]}.{last_octet}"
            ids = [f"chaos-multi-{prefix}-{run_id}-{group}-{i}"
                   for i in range(events_per_alert)]
            events.extend({
                "eventId": event_id,
                "source": "firewall",
                "host": f"{dataset.get('entityPrefix', 'multi-host')}-{run_id}-{prefix}-{group}-{i}",
                "severity": "HIGH",
                "message": "RDP connection to 3389",
                "src_ip": src_ip,
            } for i, event_id in enumerate(ids))
            expected.append(expected_alert_id(
                dataset.get("ruleId", "LATERAL-RDP"), src_ip, ids,
                str(dataset.get("tenantId") or os.environ.get("PIPELINE_TENANT_ID", "default"))))
        return events, expected

    events, expected_initial = threshold_batch("initial", 220)
    before = alert_total(token) or 0
    ingest_result = ingest(token, events)
    def observed_total(expected_count=1):
        value = alert_total(token)
        return value if value is not None and value >= before + expected_count else None

    observed = wait_for(lambda: observed_total(len(expected_initial)), timeout=120, interval=2)

    def matching_alerts(expected_ids):
        return [item for item in list_alerts(token)
                if item.get("sourceAlertId") in expected_ids]

    matching = wait_for(lambda: matching_alerts(set(expected_initial))
                        if len(matching_alerts(set(expected_initial))) == len(expected_initial) else None,
                        timeout=60, interval=2) or []

    # Stop/restart the canonical instance repeatedly. Survivors must jointly
    # own a disjoint, complete assignment during every outage, and the full
    # group must regain disjoint ownership after every restart.
    stopped = False
    cycle_results = []
    expected_all = list(expected_initial)
    matching_all = matching
    observed_post = observed
    try:
        def remaining_assignment():
            survivor_urls = urls[1:]
            if not all(direct_instance_up(url, token) for url in survivor_urls):
                return None
            stats = [direct_instance_stats(url, token) for url in survivor_urls]
            if any(not isinstance(item, dict) for item in stats):
                return None
            partitions = [set(item.get("assignedPartitions", [])) for item in stats]
            owned = set().union(*partitions)
            disjoint = sum(len(item) for item in partitions) == len(owned)
            if owned != set(range(baseline["partitions"])) or not disjoint:
                return None
            return {
                "instances": survivor_urls,
                "assignedPartitions": [sorted(item) for item in partitions],
            }

        for cycle in range(rebalance_cycles):
            control("stop-service", "detect-web")
            stopped = wait_for(
                lambda: not direct_instance_up(urls[0], token), timeout=30, interval=1)
            if not stopped:
                raise RuntimeError(
                    f"canonical Detection instance did not stop for rebalance cycle {cycle + 1}")

            after_stop = wait_for(remaining_assignment, timeout=90, interval=2)
            after_stop_partitions = (after_stop or {}).get("assignedPartitions", [])
            rebalance_ok = sum(len(item) for item in after_stop_partitions) == baseline["partitions"]
            post_events, expected_post = threshold_batch(
                f"post-rebalance-{cycle + 1}", 20 + cycle * 20)
            post_ingest = ingest(token, post_events)
            expected_all.extend(expected_post)
            observed_post = wait_for(
                lambda: observed_total(len(expected_all)), timeout=120, interval=2)
            matching_all = wait_for(
                lambda: matching_alerts(set(expected_all))
                if len(matching_alerts(set(expected_all))) == len(expected_all) else None,
                timeout=60, interval=2) or []

            control("start-service", "detect-web")
            stopped = False
            recovered = wait_for(lambda: assignments(), timeout=120, interval=2)
            cycle_results.append({
                "cycle": cycle + 1,
                "afterStop": after_stop,
                "postRebalanceIngest": post_ingest,
                "afterRestart": recovered,
                "rebalanceComplete": rebalance_ok and recovered is not None,
            })
            if not cycle_results[-1]["rebalanceComplete"]:
                break

        instance_stats = [direct_instance_stats(url, token) for url in urls]
        pending_values = [item.get("pendingEvents") for item in instance_stats if isinstance(item, dict)]
        expected_ids = set(expected_all)
        actual_ids = {item.get("sourceAlertId") for item in matching_all}
        duplicate_count = max(0, len(matching_all) - len(actual_ids))
        final_kafka = wait_for(
            lambda: (snapshot if (snapshot := kafka_snapshot())["lag"] == 0 else None),
            timeout=180, interval=3) or kafka_snapshot()
        delivery = None
        if os.environ.get("SOCP_REQUIRE_DOWNSTREAM_DRAIN", "false").lower() == "true":
            delivery = wait_for(
                lambda: (snapshot if (snapshot := delivery_evidence(expected_ids))["rows"] >= len(expected_ids) * 4
                         and snapshot["pendingOrProcessing"] == 0
                         and snapshot["undelivered"] == 0 else None),
                timeout=240, interval=3) or delivery_evidence(expected_ids)
        else:
            try:
                delivery = delivery_evidence(expected_ids)
            except RuntimeError as unavailable:
                delivery = {"unavailable": str(unavailable)}
        ck_logical = clickhouse_scalar(
            "SELECT uniqExact(tuple(tenant_id, alarm_id)) FROM alert_agg.alarm_detail")
        return {
            "initialAssignments": initial,
            "rebalanceCycles": cycle_results,
            "ingest": ingest_result,
            "alertsBefore": before,
            "alertsAfter": observed_post,
            "expectedAlertIds": sorted(expected_ids),
            "actualAlertIds": sorted(actual_ids),
            "missingAlertIds": sorted(expected_ids - actual_ids),
            "unexpectedAlertIds": sorted(actual_ids - expected_ids),
            "duplicateAlertCount": duplicate_count,
            "pendingEventsAfterRecovery": pending_values,
            "kafkaAfterRecovery": final_kafka,
            "deliveryEvidence": delivery,
            "clickHouseLogicalAlarmCount": ck_logical,
            "pass": (observed_post is not None and actual_ids == expected_ids
                       and duplicate_count == 0 and final_kafka.get("lag") == 0
                       and all(value == 0 for value in pending_values)
                       and (not isinstance(delivery, dict) or "unavailable" in delivery
                            or (delivery.get("duplicates") == 0
                                and delivery.get("pendingOrProcessing") == 0
                                and delivery.get("undelivered", 0) == 0))
                       and len(cycle_results) == rebalance_cycles
                      and all(item["rebalanceComplete"] for item in cycle_results)),
        }
    finally:
        if stopped and not direct_instance_up(urls[0], token):
            control("start-service", "detect-web")


def main():
    global DATASET_SPEC, RUN_NAMESPACE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--scenario",
        choices=("all", "detect_restart", "duplicate_delivery", "alert_web_restart",
                 "postgres_outage", "opensearch_outage", "detection_outbox_replay",
                 "multi_instance"),
        default="all")
    parser.add_argument("--count", type=int, default=20,
                        help="events used by the Detection restart scenario")
    parser.add_argument("--rebalance-cycles", type=int, default=1,
                        help="stop/restart cycles used by multi_instance (default: 1)")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET),
                        help="versioned JSON dataset specification")
    parser.add_argument("--run-id", help="stable namespace for this evidence run")
    parser.add_argument("--no-auto-cluster", action="store_true",
                        help="do not start the fixed three-instance Detection cluster")
    parser.add_argument("--output", help="optional JSON result path")
    args = parser.parse_args()
    if args.count < 5:
        parser.error("--count must be at least 5")
    if args.rebalance_cycles < 1:
        parser.error("--rebalance-cycles must be at least 1")

    try:
        DATASET_SPEC = load_dataset(args.dataset)
    except (OSError, ValueError, RuntimeError) as failure:
        parser.error(str(failure))
    RUN_NAMESPACE = args.run_id or uuid.uuid4().hex[:12]
    results = {}
    auto_cluster = False
    started_at = time.time()
    needs_multi = args.scenario == "multi_instance" or (
        args.scenario == "all" and os.environ.get("DETECTION_INSTANCE_URLS"))
    try:
        if needs_multi and not args.no_auto_cluster and not os.environ.get("DETECTION_INSTANCE_URLS"):
            start_auto_detection_cluster()
            auto_cluster = True
        token = login()

        def run(name, operation):
            try:
                results[name] = operation()
            except Exception as failure:
                results[name] = {"pass": False, "error": str(failure),
                                 "errorType": failure.__class__.__name__}

        if args.scenario in ("all", "detect_restart"):
            run("detect_restart", lambda: scenario_detection_restart(token, args.count))
        if args.scenario in ("all", "duplicate_delivery"):
            run("duplicate_delivery", lambda: scenario_duplicate_delivery(token))
        if args.scenario in ("all", "alert_web_restart"):
            run("alert_web_restart", lambda: scenario_alert_web_restart(token))
        if args.scenario in ("all", "postgres_outage"):
            run("postgres_outage", lambda: scenario_postgres_outage(token))
        if args.scenario in ("all", "opensearch_outage"):
            run("opensearch_outage", lambda: scenario_opensearch_outage(token))
        if args.scenario in ("all", "detection_outbox_replay"):
            run("detection_outbox_replay", lambda: scenario_detection_outbox_replay(token))
        if needs_multi:
            run("multi_instance", lambda: scenario_multi_instance(
                token, args.count, args.rebalance_cycles))
    except Exception as failure:
        results.setdefault("runner", {"pass": False, "error": str(failure),
                                       "errorType": failure.__class__.__name__})
    finally:
        if auto_cluster:
            stop_auto_detection_cluster()

    report = {"recordedAt": time.time(), "startedAt": started_at,
              "commitSha": os.environ.get("GITHUB_SHA", "unknown"),
              "dataset": {"path": str(Path(args.dataset)),
                          "version": DATASET_SPEC.get("version"),
                          "seed": DATASET_SPEC.get("seed")},
              "runNamespace": RUN_NAMESPACE, "topic": TOPIC, "group": GROUP,
              "results": results,
              "pass": bool(results) and all(result.get("pass") for result in results.values())}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
