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
from types import SimpleNamespace

BUILD = Path(__file__).resolve().parent
REPO = BUILD.parent
sys.path.insert(0, str(BUILD))

from ports import GATEWAY_URL, health_url, port_of  # noqa: E402
from auth_client import login_token  # noqa: E402
from middleware_images import image  # noqa: E402
from kafka_offsets import offset_snapshot  # noqa: E402
from chaos_scenarios import recovery, multi_instance, migration  # noqa: E402


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


def alerts_triggered_by(alerts, source_event_ids, rule_ids):
    """Return only alerts produced by the current scenario's source events.

    Entity values are intentionally reused by several long-running chaos
    scenarios.  They are therefore not a safe run boundary: an earlier run
    can leave a valid alert for the same entity in Alert Web.  The trigger
    event identity is stable end-to-end and uniquely scopes the oracle without
    hiding an extra alert produced from one of this scenario's events.
    """
    sources = set(source_event_ids)
    rules = set(rule_ids)
    return [item for item in alerts
            if item.get("ruleId") in rules
            and item.get("triggerEventId") in sources]


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
    context = SimpleNamespace(
        GATEWAY_URL=GATEWAY_URL,
        control=control,
        direct_instance_stats=direct_instance_stats,
        drained_kafka_snapshot=drained_kafka_snapshot,
        ingest=ingest,
        kafka_snapshot=kafka_snapshot,
        run_token=run_token,
        service_up=service_up,
        unwrap=unwrap,
        wait_for=wait_for,
    )
    return recovery.scenario_detection_restart(context, token, count)


def scenario_duplicate_delivery(token):
    context = SimpleNamespace(
        alert_total=alert_total,
        ingest=ingest,
        list_alerts=list_alerts,
        run_token=run_token,
        wait_for=wait_for,
    )
    return recovery.scenario_duplicate_delivery(context, token)


def scenario_alert_web_restart(token):
    context = SimpleNamespace(
        alert_total=alert_total,
        control=control,
        ingest=ingest,
        list_alerts=list_alerts,
        run_token=run_token,
        service_up=service_up,
        wait_for=wait_for,
    )
    return recovery.scenario_alert_web_restart(context, token)


def scenario_postgres_outage(token):
    context = SimpleNamespace(
        alert_total=alert_total,
        container_running=container_running,
        detection_urls=detection_urls,
        direct_instance_stats=direct_instance_stats,
        direct_instance_up=direct_instance_up,
        docker_container=docker_container,
        drained_kafka_snapshot=drained_kafka_snapshot,
        kafka_snapshot=kafka_snapshot,
        list_alerts=list_alerts,
        publish_detection_event=publish_detection_event,
        run_token=run_token,
        service_up=service_up,
        wait_for=wait_for,
    )
    return recovery.scenario_postgres_outage(context, token)


def scenario_opensearch_outage(token):
    context = SimpleNamespace(
        GATEWAY_URL=GATEWAY_URL,
        health_url=health_url,
        auth_headers=auth_headers,
        container_healthy=container_healthy,
        container_running=container_running,
        docker_container=docker_container,
        ingest=ingest,
        list_alerts=list_alerts,
        request=request,
        run_token=run_token,
        unwrap=unwrap,
        wait_for=wait_for,
    )
    return recovery.scenario_opensearch_outage(context, token)


def scenario_detection_outbox_replay(token):
    context = SimpleNamespace(
        ingest=ingest,
        list_alerts=list_alerts,
        psql_scalar=psql_scalar,
        run_token=run_token,
        wait_for=wait_for,
    )
    return recovery.scenario_detection_outbox_replay(context, token)


def detection_urls():
    raw_urls = os.environ.get("DETECTION_INSTANCE_URLS", "")
    values = [item.strip().rstrip("/") for item in raw_urls.split(",") if item.strip()]
    return values or [GATEWAY_URL]


def scenario_multi_instance(token, count, rebalance_cycles=1):
    context = SimpleNamespace(
        CANONICAL_TOPIC=CANONICAL_TOPIC,
        DATASET_SPEC=DATASET_SPEC,
        ROUTER_GROUP=ROUTER_GROUP,
        RUN_NAMESPACE=RUN_NAMESPACE,
        alert_total=alert_total,
        alerts_triggered_by=alerts_triggered_by,
        clickhouse_scalar=clickhouse_scalar,
        control=control,
        delivery_evidence=delivery_evidence,
        direct_instance_stats=direct_instance_stats,
        direct_instance_up=direct_instance_up,
        expected_alert_id=expected_alert_id,
        expected_ordered_alert_id=expected_ordered_alert_id,
        ingest=ingest,
        kafka_snapshot=kafka_snapshot,
        list_alerts=list_alerts,
        psql_scalar=psql_scalar,
        publish_detection_event=publish_detection_event,
        run_token=run_token,
        wait_for=wait_for,
    )
    return multi_instance.scenario_multi_instance(context, token, count, rebalance_cycles)

def scenario_routed_migration(token, count):
    context = SimpleNamespace(
        GATEWAY_URL=GATEWAY_URL,
        login_token=login_token,
        CANONICAL_TOPIC=CANONICAL_TOPIC,
        DATASET_SPEC=DATASET_SPEC,
        ROUTED_TOPIC=ROUTED_TOPIC,
        ROUTER_GROUP=ROUTER_GROUP,
        RUN_NAMESPACE=RUN_NAMESPACE,
        TOPIC=TOPIC,
        auth_headers=auth_headers,
        expected_alert_id=expected_alert_id,
        ingest=ingest,
        kafka_snapshot=kafka_snapshot,
        list_alerts=list_alerts,
        psql_scalar=psql_scalar,
        publish_detection_event=publish_detection_event,
        request=request,
        run_token=run_token,
        unwrap=unwrap,
        wait_for=wait_for,
    )
    return migration.scenario_routed_migration(context, token, count)


def scenario_routing_rollback(token, count):
    context = SimpleNamespace(
        BUILD=BUILD,
        CANONICAL_TOPIC=CANONICAL_TOPIC,
        DATASET_SPEC=DATASET_SPEC,
        GROUP=GROUP,
        REPO=REPO,
        ROUTED_TOPIC=ROUTED_TOPIC,
        ROUTER_GROUP=ROUTER_GROUP,
        RUNNER=RUNNER,
        RUN_NAMESPACE=RUN_NAMESPACE,
        auth_headers=auth_headers,
        direct_instance_stats=direct_instance_stats,
        expected_alert_id=expected_alert_id,
        ingest=ingest,
        kafka_snapshot=kafka_snapshot,
        list_alerts=list_alerts,
        psql_scalar=psql_scalar,
        request=request,
        run_token=run_token,
        stop_auto_detection_cluster=stop_auto_detection_cluster,
        unwrap=unwrap,
        wait_for=wait_for,
    )
    return migration.scenario_routing_rollback(context, token, count)


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
