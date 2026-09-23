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
import shutil
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
from middleware_images import image  # noqa: E402
from kafka_offsets import offset_snapshot  # noqa: E402


BOOTSTRAP = os.environ.get("PIPELINE_KAFKA", "127.0.0.1:9092")
CANONICAL_TOPIC = os.environ.get("SOCP_DETECT_ROUTING_SOURCE_TOPIC", "socp-events")
ROUTED_TOPIC = os.environ.get("SOCP_DETECT_ROUTING_DELIVERY_TOPIC", "socp-detection-routed-v2")
TOPIC = os.environ.get("RECOVERY_TOPIC", os.environ.get("SOCP_DETECT_INPUT_TOPIC", "socp-events"))
GROUP = os.environ.get("RECOVERY_GROUP", os.environ.get("SOCP_KAFKA_GROUP_ID", "socp-detect"))
ROUTER_GROUP = os.environ.get("SOCP_DETECT_ROUTING_SOURCE_GROUP_ID", "socp-detect-router-v2")
USER = os.environ.get("DEMO_USER", "demo")
PASSWORD = os.environ.get("DEMO_PASS", "demo123")
VECTOR_TOKEN = os.environ.get("SOCP_VECTOR_TOKEN", "dev-vector-token")
RUNNER = os.environ.get("SOCP_BASH", "bash")
DEFAULT_DATASET = BUILD / "datasets" / "chaos-v1.json"
DATASET_SPEC = {}
RUN_NAMESPACE = ""

def request(url, method="GET", body=None, headers=None, timeout=20):
    data = None if body is None else body if isinstance(body, bytes) else (body if isinstance(body, str) else json.dumps(body)).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    if body is not None and not isinstance(body, (str, bytes)) and not req.has_header("Content-type"):
        req.add_header("Content-Type", "application/json")
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
    global TOPIC
    os.environ.setdefault("SOCP_DETECT_INPUT_TOPIC", ROUTED_TOPIC)
    os.environ.setdefault("SOCP_DETECT_ROUTING_MODE", "primary")
    os.environ.setdefault("SOCP_DETECT_OUTPUT_MODE", "primary")
    os.environ.setdefault("SOCP_DETECT_ROUTING_PUBLISHER_ENABLED", "true")
    os.environ.setdefault("SOCP_DETECT_CLUSTER_MIN_PARTITIONS", "6")
    os.environ.setdefault("RECOVERY_TOPIC", os.environ["SOCP_DETECT_INPUT_TOPIC"])
    TOPIC = os.environ["RECOVERY_TOPIC"]
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
    """Unwrap the ApiResult envelope {code,message,data}; a non-zero code yields None."""
    if isinstance(body, dict) and "code" in body and "data" in body:
        return body["data"] if body.get("code") == 0 else None
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
    return None


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
    "socp-postgres": image("postgres"),
    "socp-opensearch": image("opensearch"),
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
            ["docker", "ps", "-a", "--filter", f"ancestor={image}",
             "--format", "{{{{.ID}}}}"], cwd=REPO,
            capture_output=True, text=True, timeout=15, check=False)
        container_id = next((line.strip() for line in discovered.stdout.splitlines()
                             if line.strip()), None)
        if discovered.returncode == 0 and container_id:
            return container_id
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


def publish_detection_event(event, target_topic=None):
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
        "schemaVersion": "1.0",
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
            target_topic or TOPIC, key=routing_key.encode("utf-8"),
            value=json.dumps(payload, separators=(",", ":")).encode("utf-8")).get(timeout=30)
        producer.flush(timeout=30)
        return {"published": 1, "topic": metadata.topic,
                "partition": metadata.partition, "offset": metadata.offset,
                "routingKey": routing_key}
    finally:
        producer.close(timeout=10)


def kafka_cli_snapshot(topic=None, group=None):
    topic = topic or TOPIC
    group = group or GROUP
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
         "--bootstrap-server", "localhost:9092", "--group", group, "--describe"],
        cwd=REPO, capture_output=True, text=True, timeout=30, check=False)
    if result.returncode != 0:
        return None

    partitions = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) < 6 or fields[0] != group or fields[1] != topic:
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


def kafka_snapshot(topic=None, group=None):
    topic = topic or TOPIC
    group = group or GROUP
    cli_snapshot = kafka_cli_snapshot(topic, group)
    if cli_snapshot is not None:
        return cli_snapshot
    try:
        from kafka import KafkaConsumer
        from kafka.admin import KafkaAdminClient
        from kafka.structs import TopicPartition
    except ImportError as error:
        raise RuntimeError("kafka-python is required for chaos checks") from error

    # Do not join the production consumer group just to inspect its offsets.
    # A diagnostic consumer joining the production group triggers a rebalance and can revoke
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
        partitions = consumer.partitions_for_topic(topic)
        if not partitions:
            raise RuntimeError(f"Kafka topic {topic} has no partitions")
        tps = [TopicPartition(topic, p) for p in sorted(partitions)]
        consumer.assign(tps)
        ends = consumer.end_offsets(tps)
        committed = admin.list_group_offsets({group: tps}).get(group, {})
        return offset_snapshot(ends, committed)
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
    """Match unordered threshold Alert.stableId."""
    return java_name_uuid("|".join([tenant, rule_id, entity, *sorted(event_ids)]))


def expected_ordered_alert_id(rule_id, entity, event_ids, tenant="default"):
    """Match ordered correlation Alert.stableId."""
    return java_name_uuid("|".join([tenant, rule_id, entity, *event_ids]))


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
    # The ingest boundary is shared with the long-running Vector fixture and
    # is deliberately rate-limited.  A chaos run must not turn a transient
    # one-second quota collision into a false resilience failure, so honor the
    # fixed-window limit with bounded backoff while preserving the same request
    # and authentication on every attempt.
    last_status = None
    last_body = None
    for attempt in range(6):
        status, body = request(
            ingest_url, "POST", payload, headers, timeout=30)
        if status != 429:
            if status != 200:
                raise RuntimeError(f"ingest failed HTTP {status}: {body}")
            return body
        last_status, last_body = status, body
        if attempt < 5:
            time.sleep(1.1 + attempt * 0.4)
    raise RuntimeError(f"ingest failed HTTP {last_status}: {last_body}")


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
            "severity": "HIGH",
            "message": f"Failed password for invalid user root from 198.51.100.77 port {52000 + i} ssh2",
            "src_ip": "198.51.100.77",
            "user": "root",
        } for i in range(count)]
        accepted = ingest(token, events)
        accepted_body = unwrap(accepted)
        accepted_count = int(accepted_body.get("accepted", count)) if isinstance(accepted_body, dict) else count

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
        # Dependency failure must remove readiness without killing the
        # process. Aggregate health intentionally includes OpenSearch.
        alive_status, _ = request(health_url("search-config") + "/liveness",
                                  headers=auth_headers(token), timeout=4)
        readiness_status, _ = request(health_url("search-config") + "/readiness",
                                      headers=auth_headers(token), timeout=4)
        search_alive = alive_status == 200
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
            "searchConfigReadinessStatusWhileDown": readiness_status,
            "openSearchRecovered": bool(os_ready),
            "acceptedAfterRecovery": recovery,
            "recoveryEventIndexed": bool(indexed_after_recovery),
            "pass": len(alarms) == 1 and search_alive and readiness_status == 503 and bool(os_ready)
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
    """Validate routed state correctness with an independent alert oracle.

    The evidence topology is exactly three Detection instances consuming
    exactly six routed partitions. The router consumer group is drained before
    the oracle starts so migration prewarm cannot move the baseline underneath
    the test.
    """
    raw_urls = os.environ.get("DETECTION_INSTANCE_URLS", "")
    urls = [item.strip().rstrip("/") for item in raw_urls.split(",") if item.strip()]
    if len(urls) != 3:
        raise RuntimeError("multi_instance requires exactly three Detection instance URLs")

    source_baseline = wait_for(
        lambda: (snapshot if (snapshot := kafka_snapshot(CANONICAL_TOPIC, ROUTER_GROUP))
                 and snapshot["lag"] == 0 else None),
        timeout=240, interval=3)
    if source_baseline is None:
        raise RuntimeError("canonical router group did not drain before multi-instance oracle")

    baseline = wait_for(
        lambda: (snapshot if (snapshot := kafka_snapshot()) and snapshot["lag"] == 0 else None),
        timeout=240, interval=3)
    if not baseline or baseline["partitions"] != 6:
        raise RuntimeError(
            f"multi_instance requires exactly 6 routed Kafka partitions; got {baseline}")
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
    default_tenant = str(
        dataset.get("tenantId") or os.environ.get("PIPELINE_TENANT_ID", "default"))
    digest = hashlib.sha256(
        f"{DATASET_SPEC['seed']}:{RUN_NAMESPACE}:multi-instance".encode("utf-8")
    ).digest()
    run_octets = (digest[0], digest[1])

    def threshold_batch(prefix, ip_start):
        events = []
        expected = []
        entities = set()
        for group in range(group_count):
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
                dataset.get("ruleId", "LATERAL-RDP"), src_ip, ids, default_tenant))
            entities.add(src_ip)
        return events, expected, entities

    events, expected_initial, oracle_rdp_entities = threshold_batch("initial", 220)

    # Independent cross-dimension oracle: both records share only tenant+user.
    # Host and source IP intentionally differ, so a canonical single-dimension
    # route cannot satisfy this sequence across partitions/replicas.
    cross_user = f"cross-user-{run_id}"
    cross_ids = [f"chaos-cross-user-{run_id}-failed",
                 f"chaos-cross-user-{run_id}-sudo"]
    cross_events = [
        {
            "eventId": cross_ids[0],
            "source": "auth",
            "host": f"cross-auth-host-{run_id}",
            "severity": "HIGH",
            "message": "Failed password for cross dimension user",
            "src_ip": f"198.51.100.{1 + digest[2] % 200}",
            "user": cross_user,
        },
        {
            "eventId": cross_ids[1],
            "source": "auditd",
            "host": f"cross-audit-host-{run_id}",
            "severity": "HIGH",
            "message": f"sudo: {cross_user} executed /bin/id",
            "src_ip": f"203.0.113.{1 + digest[3] % 200}",
            "user": cross_user,
        },
    ]
    events.append(cross_events[0])
    expected_cross = expected_ordered_alert_id(
        "CORR-FAIL-SUDO", cross_user, cross_ids, default_tenant)
    expected_initial.append(expected_cross)

    before = alert_total(token) or 0
    ingest_result = ingest(token, events)
    source_event_ids = {item["eventId"] for item in events}

    # CorrelationRule advances in processing order. The two canonical keys
    # intentionally differ, so Kafka cannot guarantee their relative arrival.
    # Establish the first user-dimension step durably before sending the next;
    # this remains a cross-dimension routing proof, not an event-time reorder test.
    quoted_cross_id = "'" + cross_ids[0].replace("'", "''") + "'"
    quoted_tenant = "'" + default_tenant.replace("'", "''") + "'"
    first_step = wait_for(lambda: psql_scalar(
        "detect", "select count(*) from t_detection_event "
        f"where tenant_id={quoted_tenant} and source_event_id={quoted_cross_id} "
        "and status='COMPLETED' and routing_version='detection-routing-v2' "
        "and fields_json::jsonb->>'detection_delivery_dimension'='user'") == "1",
        timeout=120, interval=2)
    if not first_step:
        raise RuntimeError("first cross-dimension correlation step did not complete on its user delivery")
    cross_followup = ingest(token, [cross_events[1]])
    source_event_ids.add(cross_events[1]["eventId"])

    # Same username split across two different tenants must never form one
    # correlation. Publish at the canonical Kafka boundary so the proof is not
    # constrained by the logged-in collector tenant.
    isolated_user = f"same-user-{run_id}"
    tenant_a = f"iso-a-{run_id}"
    tenant_b = f"iso-b-{run_id}"
    isolation_ids = [f"chaos-isolation-{run_id}-failed",
                     f"chaos-isolation-{run_id}-sudo"]
    isolation_publish = [
        publish_detection_event({
            "eventId": isolation_ids[0],
            "tenantId": tenant_a,
            "source": "auth",
            "host": f"iso-auth-{run_id}",
            "message": "Failed password isolation probe",
            "src_ip": "192.0.2.41",
            "user": isolated_user,
        }, target_topic=CANONICAL_TOPIC),
        publish_detection_event({
            "eventId": isolation_ids[1],
            "tenantId": tenant_b,
            "source": "auditd",
            "host": f"iso-audit-{run_id}",
            "message": f"sudo: {isolated_user} executed /bin/true",
            "src_ip": "192.0.2.42",
            "user": isolated_user,
        }, target_topic=CANONICAL_TOPIC),
    ]
    source_event_ids.update(isolation_ids)
    forbidden_cross_tenant = {
        expected_ordered_alert_id("CORR-FAIL-SUDO", isolated_user, isolation_ids, tenant_a),
        expected_ordered_alert_id("CORR-FAIL-SUDO", isolated_user, isolation_ids, tenant_b),
    }

    def oracle_alerts():
        values = []
        for item in list_alerts(token):
            rule = item.get("ruleId")
            entity = item.get("entity")
            if rule == dataset.get("ruleId", "LATERAL-RDP") and entity in oracle_rdp_entities:
                values.append(item)
            elif rule == "CORR-FAIL-SUDO" and entity == cross_user:
                values.append(item)
        return values

    def observed_oracle(expected_ids):
        values = oracle_alerts()
        actual = {item.get("sourceAlertId") for item in values if item.get("sourceAlertId")}
        return values if actual == set(expected_ids) else None

    expected_all = list(expected_initial)
    matching_all = wait_for(
        lambda: observed_oracle(expected_all), timeout=180, interval=2) or oracle_alerts()

    # Stop/restart one instance repeatedly. Survivors must jointly own a
    # disjoint, complete six-partition assignment during every outage.
    stopped = False
    cycle_results = []
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

            post_events, expected_post, post_entities = threshold_batch(
                f"post-rebalance-{cycle + 1}", 20 + cycle * 20)
            post_ingest = ingest(token, post_events)
            source_event_ids.update(item["eventId"] for item in post_events)
            expected_all.extend(expected_post)
            oracle_rdp_entities.update(post_entities)

            matching_all = wait_for(
                lambda: observed_oracle(expected_all), timeout=180, interval=2) or oracle_alerts()

            control("start-service", "detect-web")
            stopped = False
            recovered = wait_for(assignments, timeout=120, interval=2)
            cycle_results.append({
                "cycle": cycle + 1,
                "afterStop": after_stop,
                "postRebalanceIngest": post_ingest,
                "afterRestart": recovered,
                "rebalanceComplete": rebalance_ok and recovered is not None,
            })
            if not cycle_results[-1]["rebalanceComplete"]:
                break

        # Source must drain first; only then is zero routed lag a stable end state.
        source_after = wait_for(
            lambda: (snapshot if (snapshot := kafka_snapshot(CANONICAL_TOPIC, ROUTER_GROUP))
                     and snapshot["lag"] == 0 else None),
            timeout=240, interval=3) or kafka_snapshot(CANONICAL_TOPIC, ROUTER_GROUP)
        final_kafka = wait_for(
            lambda: (snapshot if (snapshot := kafka_snapshot()) and snapshot["lag"] == 0 else None),
            timeout=240, interval=3) or kafka_snapshot()

        matching_all = wait_for(
            lambda: observed_oracle(expected_all), timeout=120, interval=2) or oracle_alerts()
        expected_ids = set(expected_all)
        actual_ids = {item.get("sourceAlertId") for item in matching_all
                      if item.get("sourceAlertId")}
        duplicate_count = max(0, len(matching_all) - len(actual_ids))

        forbidden_sql = ",".join(
            "'" + value.replace("'", "''") + "'" for value in sorted(forbidden_cross_tenant))
        forbidden_count = int(psql_scalar(
            "alert",
            f"select count(*) from t_alarm where source_alert_id in ({forbidden_sql})") or 0)

        quoted_sources = ",".join(
            "'" + value.replace("'", "''") + "'" for value in sorted(source_event_ids))
        route_where = f"source_event_id in ({quoted_sources})"
        route_rows = int(psql_scalar(
            "detect", f"select count(*) from t_detection_route_outbox where {route_where}") or 0)
        route_distinct = int(psql_scalar(
            "detect", f"select count(distinct delivery_id) from t_detection_route_outbox where {route_where}") or 0)
        route_unfinished = int(psql_scalar(
            "detect", f"select count(*) from t_detection_route_outbox where {route_where} and status <> 'PUBLISHED'") or 0)
        route_missing_position = int(psql_scalar(
            "detect", f"select count(*) from t_detection_route_outbox where {route_where} "
            "and status='PUBLISHED' and (source_topic is null or source_partition is null "
            "or source_offset is null or delivery_topic is null or delivery_partition is null "
            "or delivery_offset is null)") or 0)
        max_fan_out = int(psql_scalar(
            "detect", "select coalesce(max(c),0) from (select source_event_id,count(*) c "
            f"from t_detection_route_outbox where {route_where} group by source_event_id) routed") or 0)

        source_receipt_rows = int(psql_scalar(
            "detect", f"select count(*) from t_detection_route_source where {route_where}") or 0)
        source_receipt_positions = int(psql_scalar(
            "detect", f"select count(distinct source_topic || ':' || source_partition::text || ':' || source_offset::text) "
            f"from t_detection_route_source where {route_where}") or 0)

        journal_rows = int(psql_scalar(
            "detect", f"select count(*) from t_detection_event where {route_where}") or 0)
        journal_distinct = int(psql_scalar(
            "detect", f"select count(distinct delivery_id) from t_detection_event where {route_where}") or 0)
        journal_sources = int(psql_scalar(
            "detect", f"select count(distinct source_event_id) from t_detection_event where {route_where}") or 0)
        journal_untraceable = int(psql_scalar(
            "detect", f"select count(*) from t_detection_event where {route_where} "
            "and (source_topic is null or source_partition is null or source_offset is null "
            "or delivery_topic is null or delivery_partition is null or delivery_offset is null)") or 0)

        # Source coverage alone can hide an entire execution class: a source's
        # stateful copy may complete while its stateless copy is rejected.
        # Reconcile every tenant-scoped published delivery against completion.
        route_missing_completed_journal = int(psql_scalar(
            "detect", "select count(*) from t_detection_route_outbox r "
            "left join t_detection_event j on j.tenant_id=r.tenant_id "
            "and j.delivery_id=r.delivery_id "
            f"where r.source_event_id in ({quoted_sources}) "
            "and (j.delivery_id is null or j.status <> 'COMPLETED')") or 0)
        stateless_routes = int(psql_scalar(
            "detect", f"select count(*) from t_detection_route_outbox where {route_where} "
            "and route_kind='STATELESS'") or 0)
        stateless_completed = int(psql_scalar(
            "detect", "select count(*) from t_detection_route_outbox r "
            "join t_detection_event j on j.tenant_id=r.tenant_id "
            "and j.delivery_id=r.delivery_id "
            f"where r.source_event_id in ({quoted_sources}) "
            "and r.route_kind='STATELESS' and j.status='COMPLETED'") or 0)

        instance_stats = [direct_instance_stats(url, token) for url in urls]
        pending_values = [item.get("pendingEvents") for item in instance_stats
                          if isinstance(item, dict)]

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
        evidence = {
            "sourceEventCount": len(source_event_ids),
            "routeRows": route_rows,
            "routeDistinctDeliveries": route_distinct,
            "routeUnfinished": route_unfinished,
            "routeMissingTransportPosition": route_missing_position,
            "maximumObservedFanOut": max_fan_out,
            "sourceReceiptRows": source_receipt_rows,
            "sourceReceiptDistinctPositions": source_receipt_positions,
            "journalRows": journal_rows,
            "journalDistinctDeliveries": journal_distinct,
            "journalDistinctSources": journal_sources,
            "journalUntraceableRows": journal_untraceable,
            "routeMissingCompletedJournal": route_missing_completed_journal,
            "statelessRouteRows": stateless_routes,
            "statelessCompletedJournalRows": stateless_completed,
        }
        return {
            "sourceRouterBaseline": source_baseline,
            "routedDetectionBaseline": baseline,
            "initialAssignments": initial,
            "rebalanceCycles": cycle_results,
            "ingest": ingest_result,
            "crossDimensionFirstStepCompleted": bool(first_step),
            "crossDimensionFollowupIngest": cross_followup,
            "isolationPublishes": isolation_publish,
            "expectedAlertIds": sorted(expected_ids),
            "actualAlertIds": sorted(actual_ids),
            "missingAlertIds": sorted(expected_ids - actual_ids),
            "unexpectedAlertIds": sorted(actual_ids - expected_ids),
            "duplicateAlertCount": duplicate_count,
            "forbiddenCrossTenantAlertIds": sorted(forbidden_cross_tenant),
            "forbiddenCrossTenantAlertCount": forbidden_count,
            "pendingEventsAfterRecovery": pending_values,
            "sourceKafkaAfterRecovery": source_after,
            "routedKafkaAfterRecovery": final_kafka,
            "routingAndJournalEvidence": evidence,
            "deliveryEvidence": delivery,
            "clickHouseLogicalAlarmCount": ck_logical,
            "pass": (
                actual_ids == expected_ids
                and duplicate_count == 0
                and forbidden_count == 0
                and source_after.get("lag") == 0
                and final_kafka.get("lag") == 0
                and final_kafka.get("partitions", 0) == 6
                and all(value == 0 for value in pending_values)
                and route_rows == route_distinct
                and route_unfinished == 0
                and route_missing_position == 0
                and 0 < max_fan_out <= 5
                and source_receipt_rows == len(source_event_ids)
                and source_receipt_positions == source_receipt_rows
                and journal_rows == journal_distinct
                and journal_rows == route_rows
                and route_missing_completed_journal == 0
                and stateless_routes == len(source_event_ids)
                and stateless_completed == stateless_routes
                and journal_sources == len(source_event_ids)
                and journal_untraceable == 0
                and (not isinstance(delivery, dict) or "unavailable" in delivery
                     or (delivery.get("duplicates") == 0
                         and delivery.get("pendingOrProcessing") == 0
                         and delivery.get("undelivered", 0) == 0))
                and len(cycle_results) == rebalance_cycles
                and all(item["rebalanceComplete"] for item in cycle_results)
            ),
        }
    finally:
        if stopped and not direct_instance_up(urls[0], token):
            control("start-service", "detect-web")

def scenario_routed_migration(token, count):
    """Live routed-migration integrity on the primary routed cluster.

    Three evidence phases demanded by the cross-dimension contract:
      1. duplicate canonical deliveries: republishing the same business events
         at new source offsets must create new source receipts but must not
         manufacture new fan-out delivery identities or a second alert;
      2. the API rejects incompatible activation without persisting a change;
         an administratively injected unsupported ACTIVE rule then fails closed
         (visible status + uncommitted source), not keep a "healthy detection"
         illusion, and removing the rule must resume exactly the deferred work;
      3. rollback: restarting the cluster on the legacy input topic keeps
         detection formal (LEGACY_PARTIAL is reported, never cross-dimension
         completeness) and restores cleanly to the routed generation.
    """
    if TOPIC != ROUTED_TOPIC:
        raise RuntimeError("routed migration requires the cluster to consume the routed topic")
    urls = [item.strip().rstrip("/")
            for item in os.environ.get("DETECTION_INSTANCE_URLS", "").split(",")
            if item.strip()]
    if len(urls) != 3:
        raise RuntimeError("routed migration requires three Detection instance URLs")
    instance = urls[0]

    def drained():
        source = kafka_snapshot(CANONICAL_TOPIC, ROUTER_GROUP)
        routed = kafka_snapshot()
        return (source["lag"] == 0 and routed["lag"] == 0) or None

    if wait_for(drained, timeout=240, interval=3) is None:
        raise RuntimeError("routed migration requires a fully drained baseline")

    dataset = DATASET_SPEC.get("multiInstance", {})
    events_per_alert = int(dataset.get("eventsPerAlert", 5))
    group_count = max(1, count // max(1, int(dataset.get("groupsPerBatchDivisor", events_per_alert))))
    digest = hashlib.sha256(f"{DATASET_SPEC['seed']}:{RUN_NAMESPACE}:routed-migration".encode()).digest()
    run_id = run_token("routed-migration")

    def batch(prefix, ip_start):
        events, expected, entities = [], [], set()
        for group in range(group_count):
            last_octet = 1 + ((ip_start + group) % 253)
            src_ip = f"{dataset.get('sourceIpPrefix', '10.240')}.{digest[4] % 251}.{last_octet}"
            ids = [f"chaos-rmig-{prefix}-{run_id}-{group}-{i}" for i in range(events_per_alert)]
            events.extend({
                "eventId": event_id,
                "source": "firewall",
                "host": f"{dataset.get('entityPrefix', 'rmig-host')}-{run_id}-{prefix}-{group}-{i}",
                "severity": "HIGH",
                "message": "RDP connection to 3389",
                "src_ip": src_ip,
            } for i, event_id in enumerate(ids))
            expected.append(expected_alert_id(
                dataset.get("ruleId", "LATERAL-RDP"), src_ip, ids, "default"))
            entities.add(src_ip)
        return events, expected, entities

    def observed(entities_of_interest, expected_ids):
        values = []
        for item in list_alerts(token):
            if item.get("ruleId") == dataset.get("ruleId", "LATERAL-RDP") \
                    and item.get("entity") in entities_of_interest:
                values.append(item)
        actual = {item.get("sourceAlertId") for item in values if item.get("sourceAlertId")}
        return values if actual == set(expected_ids) else None

    def receipts(source_ids):
        quoted = ",".join("'" + value.replace("'", "''") + "'" for value in sorted(source_ids))
        where = f"source_event_id in ({quoted})"
        return (
            int(psql_scalar("detect", f"select count(*) from t_detection_route_source where {where}") or 0),
            int(psql_scalar("detect", f"select count(*) from t_detection_route_outbox where {where}") or 0),
            int(psql_scalar("detect",
                            f"select count(distinct delivery_id) from t_detection_route_outbox where {where}") or 0),
            psql_scalar("detect", "select coalesce(string_agg(delivery_id, ',' order by delivery_id), '') "
                        f"from t_detection_route_outbox where {where}"),
        )

    # -- phase 1: duplicate canonical deliveries --------------------------------
    events_a, expected_a, entities_a = batch("dup", 40)
    ingest(token, events_a)
    matched_a = wait_for(lambda: observed(entities_a, expected_a), timeout=180, interval=2)
    if matched_a is None:
        raise RuntimeError("routed migration phase 1 baseline alerts never materialized")
    receipts_before = receipts({item["eventId"] for item in events_a})
    for item in events_a:
        publish_detection_event(item, target_topic=CANONICAL_TOPIC)
    if wait_for(drained, timeout=240, interval=3) is None:
        raise RuntimeError("router did not drain duplicate canonical deliveries")
    receipts_after = receipts({item["eventId"] for item in events_a})
    settled = wait_for(lambda: observed(entities_a, expected_a), timeout=30, interval=2)
    duplicate_phase = {
        "receiptsBefore": receipts_before[0], "receiptsAfter": receipts_after[0],
        "deliveryRowsBefore": receipts_before[1], "deliveryRowsAfter": receipts_after[1],
        "distinctDeliveriesAfter": receipts_after[2],
        "deliveryIdentitiesUnchanged": receipts_after[3] == receipts_before[3],
        "alertIdsAfter": sorted(expected_a),
    }
    if receipts_after[0] <= receipts_before[0]:
        raise RuntimeError(f"duplicate deliveries did not create new source receipts: {duplicate_phase}")
    # One canonical event can legitimately fan out across several dimensions.
    # Retries must preserve the entire original identity set, not force one row
    # per business event or merely keep the same aggregate row count.
    if (receipts_before[2] < len(events_a) or receipts_after[1:] != receipts_before[1:]
            or receipts_after[1] != receipts_after[2]):
        raise RuntimeError(f"duplicate deliveries manufactured extra delivery identities: {duplicate_phase}")
    if settled is None:
        raise RuntimeError(f"duplicate deliveries changed the alert set: {duplicate_phase}")

    # -- phase 2: unsupported ACTIVE rule fails the router closed ----------------
    bad_rule = {
        "id": f"RMIG-BAD-{run_id}".upper(),
        "name": "routed migration incompatible rule",
        "type": "threshold",
        "severity": "HIGH",
        "window": "5m",
        "threshold": 5,
        "groupBy": "proc_name",
        # Valid DSL in TESTING, but activation adds a new dimension to the
        # pinned topology. A mismatched field would be rejected earlier as 400.
        "routingField": "proc_name",
        "version": "1",
        "match": [{"field": "source", "op": "eq", "value": "firewall"}],
    }
    status, body = request(f"{instance}/detect-web/api/v1/rules", method="POST",
                           body=json.dumps(bad_rule),
                           headers={**auth_headers(token), "Content-Type": "application/json"},
                           timeout=20)
    if status != 200:
        raise RuntimeError(f"could not create incompatible rule: {status} {body}")
    created_rule = unwrap(body)
    quoted_rule_id = "'" + bad_rule["id"].replace("'", "''") + "'"
    rule_where = f"tenant_id='default' and rule_id={quoted_rule_id}"
    stored_spec = psql_scalar("detect", f"select spec from t_rule where {rule_where}")
    if json.loads(stored_spec).get("status") != "TESTING":
        raise RuntimeError("incompatible rule did not begin in the review queue")
    # Exercise the topology conflict after the separate activation permission
    # check; the analyst session used for normal queries cannot activate rules.
    activation_token = login_token(GATEWAY_URL, os.environ.get("RULE_VERIFY_USERNAME", "admin"),
                                   os.environ.get("RULE_VERIFY_PASSWORD", "admin123"))
    status, body = request(f"{instance}/detect-web/api/v1/rules/{bad_rule['id']}/activate",
                           method="POST", headers={**auth_headers(activation_token),
                           "If-Match": '"' + created_rule["revisionToken"] + '"'}, timeout=20)
    if status != 409:
        raise RuntimeError(f"incompatible activation was not rejected: {status} {body}")
    if psql_scalar("detect", f"select spec from t_rule where {rule_where}") != stored_spec:
        raise RuntimeError("rejected activation changed the persisted rule")

    # This scenario already requires disposable Compose infrastructure. Model
    # a corrupt restore/administrative write so the runtime fail-closed check
    # remains covered even though normal API writes now prevent this state.
    quoted_spec = "'" + stored_spec.replace("'", "''") + "'"
    injected = psql_scalar(
        "detect", "with injected as (update t_rule set "
        "spec=(spec::jsonb || '{\"status\":\"ACTIVE\",\"enabled\":true,"
        "\"routingField\":\"service_name\"}'::jsonb)::text "
        f"where {rule_where} and spec={quoted_spec} returning rule_id) "
        "select rule_id from injected")
    if injected != bad_rule["id"]:
        raise RuntimeError("could not inject the isolated persisted-rule fault")

    def plan_unsupported():
        code, plan_body = request(f"{instance}/detect-web/api/v1/routing-plan",
                                  headers=auth_headers(token), timeout=10)
        plan = unwrap(plan_body) if code == 200 else None
        if not isinstance(plan, dict) or plan.get("status") != "UNSUPPORTED":
            return None
        reasons = [rule for rule in plan.get("rules", [])
                   if rule.get("ruleId") == bad_rule["id"] and rule.get("status") == "UNSUPPORTED"]
        return plan if reasons else None

    try:
        plan = wait_for(plan_unsupported, timeout=60, interval=2)
        if plan is None:
            raise RuntimeError("routing plan never surfaced the incompatible ACTIVE rule as UNSUPPORTED")

        events_b, expected_b, entities_b = batch("blocked", 120)
        ingest(token, events_b)
        time.sleep(20)
        blocked_alerts = observed(entities_b, expected_b)
        blocked_lag = kafka_snapshot(CANONICAL_TOPIC, ROUTER_GROUP)
        if blocked_alerts is not None:
            raise RuntimeError("router kept delivering as if healthy despite the unsupported rule")
        if blocked_lag["lag"] == 0:
            raise RuntimeError("router committed canonical offsets despite failing closed")

    finally:
        # Restore the deliberately corrupted fixture before conditional deletion.
        restored = psql_scalar(
            "detect", f"with restored as (update t_rule set spec={quoted_spec} "
            f"where {rule_where} returning rule_id) select rule_id from restored")
        if restored != bad_rule["id"]:
            raise RuntimeError("could not restore the isolated persisted-rule fault")
        status, body = request(f"{instance}/detect-web/api/v1/rules/{bad_rule['id']}",
                               headers=auth_headers(token), timeout=20)
        if status != 200:
            raise RuntimeError(f"could not read restored rule: {status}")
        status, _ = request(f"{instance}/detect-web/api/v1/rules/{bad_rule['id']}", method="DELETE",
                            headers={**auth_headers(token), "If-Match": '"' + unwrap(body)["revisionToken"] + '"'},
                            timeout=20)
        if status != 200:
            raise RuntimeError(f"could not delete incompatible rule: {status}")
    def plan_supported():
        code, plan_body = request(f"{instance}/detect-web/api/v1/routing-plan",
                                  headers=auth_headers(token), timeout=10)
        plan = unwrap(plan_body) if code == 200 else None
        return plan if isinstance(plan, dict) and plan.get("status") == "SUPPORTED" else None

    if wait_for(plan_supported, timeout=90, interval=3) is None:
        raise RuntimeError("routing plan did not recover after the rule removal")
    if wait_for(drained, timeout=240, interval=3) is None:
        raise RuntimeError("canonical source did not resume draining after the fix")
    deferred_alerts = wait_for(lambda: observed(entities_b, expected_b), timeout=180, interval=2)
    if deferred_alerts is None:
        raise RuntimeError("work deferred by the fail-closed router was never recovered")

    return {
        "phase1DuplicateDeliveries": duplicate_phase,
        "phase2UnsupportedPlan": {
            "ruleId": bad_rule["id"],
            "apiActivationStatus": 409,
            "rejectedActivationPreservedSpec": True,
            "persistedFaultInjected": True,
            "planReason": plan.get("rules"),
            "blockedKafkaLag": blocked_lag,
            "blockedAlertCount": len(blocked_alerts or []),
        },
        "phase2RecoveredAfterRemoval": True,
        "pass": True,
    }


def scenario_routing_rollback(token, count):
    """Rollback evidence: legacy input keeps a formal output path, never claims
    cross-dimension completeness, and the routed generation restores cleanly."""
    urls = [item.strip().rstrip("/")
            for item in os.environ.get("DETECTION_INSTANCE_URLS", "").split(",")
            if item.strip()]
    if len(urls) != 3:
        raise RuntimeError("rollback evidence requires three Detection instance URLs")
    dataset = DATASET_SPEC.get("multiInstance", {})
    events_per_alert = int(dataset.get("eventsPerAlert", 5))
    group_count = max(1, count // max(1, int(dataset.get("groupsPerBatchDivisor", events_per_alert))))
    digest = hashlib.sha256(f"{DATASET_SPEC['seed']}:{RUN_NAMESPACE}:routing-rollback".encode()).digest()
    run_id = run_token("routing-rollback")
    evidence_dir = REPO / ".cache" / "chaos" / f"rollback-{run_id}"
    evidence_dir.mkdir(parents=True, exist_ok=True)

    def batch(prefix, ip_start):
        events, expected = [], []
        for group in range(group_count):
            last_octet = 1 + ((ip_start + group) % 253)
            src_ip = f"{dataset.get('sourceIpPrefix', '10.240')}.{digest[5] % 251}.{last_octet}"
            ids = [f"chaos-rrb-{prefix}-{run_id}-{group}-{i}" for i in range(events_per_alert)]
            events.extend({
                "eventId": event_id,
                "source": "firewall",
                "host": f"rrb-host-{run_id}-{prefix}-{group}-{i}",
                "severity": "HIGH",
                "message": "RDP connection to 3389",
                "src_ip": src_ip,
            } for i, event_id in enumerate(ids))
            expected.append(expected_alert_id(
                dataset.get("ruleId", "LATERAL-RDP"), src_ip, ids, "default"))
        return events, expected

    def restart_cluster(routing_mode, input_topic, publisher_enabled):
        stop_auto_detection_cluster()
        # The startup helper truncates each instance log. Preserve the previous
        # generation after shutdown, especially the failed legacy generation
        # before the mandatory cleanup restores primary mode.
        log_dir = REPO / ".cache" / "detection-cluster"
        saved = evidence_dir / f"before-{routing_mode}"
        saved.mkdir(parents=True, exist_ok=True)
        for path in [*log_dir.glob("*.log"), log_dir / "manifest.env"]:
            if path.is_file():
                shutil.copy2(path, saved / path.name)
        env = dict(os.environ)
        env.update({
            "SOCP_DETECT_INPUT_TOPIC": input_topic,
            "SOCP_DETECT_ROUTING_MODE": routing_mode,
            "SOCP_DETECT_OUTPUT_MODE": "primary",
            "SOCP_DETECT_ROUTING_PUBLISHER_ENABLED": publisher_enabled,
            "SOCP_DETECT_CLUSTER_MIN_PARTITIONS": "6",
        })
        result = subprocess.run([RUNNER, str(BUILD / "detection-cluster.sh"), "start"],
                                cwd=REPO, capture_output=True, text=True,
                                timeout=240, check=False, env=env)
        if result.returncode != 0:
            raise RuntimeError(f"cluster restart ({routing_mode}) failed: {result.stderr[-800:]}")
        if routing_mode == "legacy":
            # The legacy generation commits its own group directly on the
            # canonical topic; drain that instead of the router group.
            ok = wait_for(lambda: (kafka_snapshot(input_topic) or {}).get("lag") == 0,
                          timeout=240, interval=3)
        else:
            ok = wait_for(lambda: ((kafka_snapshot(CANONICAL_TOPIC, ROUTER_GROUP) or {}).get("lag") == 0
                                   and (kafka_snapshot() or {}).get("lag") == 0),
                          timeout=240, interval=3)
        if ok is None:
            raise RuntimeError(f"detection did not drain after restart in {routing_mode} mode")

    def capture_legacy_evidence(events, expected, entities):
        evidence = {"expectedAlertIds": sorted(expected), "sourceEvents": events}
        probes = {
            "kafka": lambda: kafka_snapshot(CANONICAL_TOPIC, GROUP),
            "alerts": lambda: [item for item in list_alerts(token)
                               if item.get("ruleId") == dataset.get("ruleId", "LATERAL-RDP")
                               and item.get("entity") in entities],
            "instances": lambda: [direct_instance_stats(url, token) for url in urls],
            "routingPlan": lambda: request(f"{urls[0]}/detect-web/api/v1/routing-plan",
                                            headers=auth_headers(token), timeout=10)[1],
        }
        quoted = ",".join("'" + item["eventId"].replace("'", "''") + "'" for item in events)
        probes["journal"] = lambda: json.loads(psql_scalar(
            "detect", "select coalesce(json_agg(row_to_json(e)), '[]'::json)::text from "
            "(select source_event_id,delivery_id,status,status_reason,occurred_at,"
            "source_topic,source_partition,source_offset,delivery_topic,delivery_partition,"
            "delivery_offset,fields_json,result_json from t_detection_event "
            f"where tenant_id='default' and source_event_id in ({quoted})) e"))
        for name, probe in probes.items():
            try:
                evidence[name] = probe()
            except Exception as error:
                evidence[name] = {"error": str(error)}
        (evidence_dir / "legacy-state.json").write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return evidence

    restored = False
    try:
        restart_cluster("legacy", CANONICAL_TOPIC, "false")
        events, expected = batch("legacy", 160)
        ingest(token, events)
        entities = {item["src_ip"] for item in events}

        def legacy_alerts():
            values = [item for item in list_alerts(token)
                      if item.get("ruleId") == dataset.get("ruleId", "LATERAL-RDP")
                      and item.get("entity") in entities]
            actual = {item.get("sourceAlertId") for item in values if item.get("sourceAlertId")}
            return values if actual == set(expected) else None

        matched = wait_for(legacy_alerts, timeout=240, interval=2)
        legacy_evidence = capture_legacy_evidence(events, expected, entities)
        if matched is None:
            actual = sorted(str(item.get("sourceAlertId") or "")
                            for item in legacy_evidence.get("alerts", [])
                            if isinstance(item, dict))
            raise RuntimeError("rollback to the legacy input topic lost the formal alert path; "
                               f"expected={sorted(expected)} actual={actual}; "
                               f"evidence={evidence_dir.relative_to(REPO)}")
        code, body = request(f"{urls[0]}/detect-web/api/v1/routing-plan",
                             headers=auth_headers(token), timeout=10)
        plan = unwrap(body) if code == 200 else None
        deployment = json.dumps((plan or {}).get("deployment", {}))
        if "LEGACY_PARTIAL" not in deployment:
            raise RuntimeError(
                f"legacy rollback must report LEGACY_PARTIAL, got: {deployment[:400]}")
        restart_cluster("primary", ROUTED_TOPIC, "true")
        restored = True
        return {"legacyFormalOutputRecovered": True,
                "legacyReportsPartial": True,
                "legacyExpectedAlertIds": sorted(expected),
                "legacyActualAlertIds": sorted(item.get("sourceAlertId") for item in matched),
                "legacyEvidence": str(evidence_dir.relative_to(REPO)),
                "routedGenerationRestored": True,
                "pass": True}
    finally:
        if not restored:
            try:
                restart_cluster("primary", ROUTED_TOPIC, "true")
            except Exception:
                pass


def main():
    global DATASET_SPEC, RUN_NAMESPACE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--scenario",
        choices=("all", "detect_restart", "duplicate_delivery", "alert_web_restart",
                 "postgres_outage", "opensearch_outage", "detection_outbox_replay",
                 "multi_instance", "routed_migration", "routing_rollback"),
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
    needs_multi = args.scenario in ("multi_instance", "routed_migration", "routing_rollback") or (
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
        if args.scenario == "routed_migration":
            run("routed_migration", lambda: scenario_routed_migration(token, args.count))
        if args.scenario == "routing_rollback":
            run("routing_rollback", lambda: scenario_routing_rollback(token, args.count))
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
