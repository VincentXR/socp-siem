"""Scenario implementation; the CLI supplies an explicit fixture context."""
from __future__ import annotations
import hashlib
import json
import os
import shutil
import subprocess
import time
import urllib.parse
from pathlib import Path

def scenario_detection_restart(ctx, token, count):
    baseline = ctx.drained_kafka_snapshot()
    if baseline is None:
        raise RuntimeError("Kafka snapshot unavailable; check kafka-python and the broker")
    if baseline["lag"] != 0:
        raise RuntimeError(f"detection restart scenario requires a drained baseline, got {baseline}")
    stopped = False
    try:
        ctx.control("stop-service", "detect-web")
        stopped = ctx.wait_for(lambda: not ctx.service_up("detect-web", token), timeout=30)
        if not stopped:
            raise RuntimeError("detect-web did not stop")
        run_id = ctx.run_token("detect-restart")
        events = [{
            "eventId": f"chaos-restart-{run_id}-{i}",
            "source": "auth",
            "host": f"chaos-restart-{run_id}",
            "severity": "HIGH",
            "message": f"Failed password for invalid user root from 198.51.100.77 port {52000 + i} ssh2",
            "src_ip": "198.51.100.77",
            "user": "root",
        } for i in range(count)]
        accepted = ctx.ingest(token, events)
        accepted_body = ctx.unwrap(accepted)
        accepted_count = int(accepted_body.get("accepted", count)) if isinstance(accepted_body, dict) else count

        def queued_snapshot():
            snapshot = ctx.kafka_snapshot()
            return snapshot if snapshot and snapshot["end"] >= baseline["end"] + accepted_count else None

        queued = ctx.wait_for(queued_snapshot, timeout=30, interval=1) or ctx.kafka_snapshot()
        if queued is None:
            raise RuntimeError("Kafka backlog snapshot unavailable while detect-web was stopped")
        ctx.control("start-service", "detect-web")
        stopped = False
        recovered = ctx.wait_for(lambda: ctx.kafka_snapshot() if ctx.service_up("detect-web", token) else None,
                             timeout=120, interval=3)
        if recovered is None:
            raise RuntimeError("detect-web did not recover")

        def drained_snapshot():
            snapshot = ctx.kafka_snapshot()
            return snapshot if snapshot and snapshot["lag"] == 0 else None

        recovered = ctx.wait_for(drained_snapshot, timeout=120, interval=3) or ctx.kafka_snapshot()
        if recovered is None:
            raise RuntimeError("Kafka snapshot unavailable while waiting for Detection recovery")
        stats = ctx.direct_instance_stats(ctx.GATEWAY_URL, token)
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
        if stopped and not ctx.service_up("detect-web", token):
            ctx.control("start-service", "detect-web")


def scenario_duplicate_delivery(ctx, token):
    run_id = ctx.run_token("duplicate-delivery")
    event_id = f"chaos-duplicate-{run_id}"
    event = {
        "eventId": event_id,
        "source": "auth",
        "host": f"chaos-duplicate-{run_id}",
        "severity": "HIGH",
        "message": "sudo: chaos duplicate-delivery probe",
    }
    before = ctx.alert_total(token) or 0
    accepted = ctx.ingest(token, [event, event])
    def recovered_total():
        value = ctx.alert_total(token)
        return value if value is not None and value >= before + 1 else None

    after = ctx.wait_for(recovered_total, timeout=60, interval=1)
    # Pattern alert ids are derived from rule/entity/evidence, not event id;
    # sourceAlertId is therefore checked by counting the matching host/rule.
    all_alerts = ctx.list_alerts(token)
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


def scenario_alert_web_restart(ctx, token):
    """Verify Detection's durable outbox survives an Alert Web outage."""
    if not ctx.service_up("detect-web", token):
        raise RuntimeError("detect-web must be healthy before alert_web_restart")
    run_id = ctx.run_token("alert-web-restart")
    host = f"chaos-alert-web-{run_id}"
    event = {
        "eventId": f"chaos-alert-web-{run_id}",
        "source": "auth",
        "host": host,
        "severity": "CRITICAL",
        "message": "sudo: alert-web outage delivery probe",
    }
    before = ctx.alert_total(token) or 0
    stopped = False
    try:
        ctx.control("stop-service", "alert-web")
        stopped = ctx.wait_for(lambda: not ctx.service_up("alert-web", token), timeout=30, interval=1)
        if not stopped:
            raise RuntimeError("alert-web did not stop")
        accepted = ctx.ingest(token, [event])
        time.sleep(5)
        while_down = ctx.alert_total(token)
        ctx.control("start-service", "alert-web")
        stopped = False

        def recovered_total():
            value = ctx.alert_total(token)
            return value if value is not None and value >= before + 1 else None

        recovered = ctx.wait_for(recovered_total, timeout=180, interval=2)
        matching = [item for item in ctx.list_alerts(token)
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
        if stopped and not ctx.service_up("alert-web", token):
            ctx.control("start-service", "alert-web")


def scenario_postgres_outage(ctx, token):
    """Prove PostgreSQL failure retains Kafka backlog and recovers exactly once."""
    baseline = ctx.drained_kafka_snapshot()
    if not baseline or baseline["lag"] != 0:
        raise RuntimeError(f"postgres_outage requires a drained baseline, got {baseline}")
    run_id = ctx.run_token("postgres-outage")
    host = f"chaos-pg-{run_id}"
    event = {
        "eventId": f"chaos-pg-{run_id}",
        "source": "auth",
        "host": host,
        "severity": "CRITICAL",
        "message": "sudo: postgres outage recovery probe",
    }
    before = ctx.alert_total(token) or 0
    stopped = False
    try:
        ctx.docker_container("stop", "socp-postgres")
        stopped = True
        ctx.wait_for(lambda: not ctx.container_running("socp-postgres"), timeout=30, interval=1)
        # PostgreSQL is shared by ingress and Detection in this deployment.
        # Publish on Kafka directly so this scenario actually exercises
        # Detection's durable backlog and database recovery boundary.
        accepted = ctx.publish_detection_event(event)

        def queued_snapshot():
            snapshot = ctx.kafka_snapshot()
            return snapshot if snapshot and snapshot["end"] >= baseline["end"] + 1 else None

        queued = ctx.wait_for(queued_snapshot, timeout=30, interval=1) or ctx.kafka_snapshot()
        ctx.docker_container("start", "socp-postgres")
        stopped = False

        recovered_services = ctx.wait_for(
            lambda: all(ctx.direct_instance_up(url, token) for url in ctx.detection_urls())
                    and ctx.service_up("alert-web", token),
            timeout=180, interval=3)

        def matching():
            values = [item for item in ctx.list_alerts(token)
                      if item.get("entity") == host and item.get("ruleId") == "AUTH-PRIVESC"]
            return values if len(values) == 1 else None

        alarms = ctx.wait_for(matching, timeout=240, interval=2) or []
        drained = ctx.wait_for(
            lambda: (snapshot if (snapshot := ctx.kafka_snapshot())["lag"] == 0 else None),
            timeout=240, interval=3) or ctx.kafka_snapshot()
        stats = [ctx.direct_instance_stats(url, token) for url in ctx.detection_urls()]
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
            "alertCountAfter": ctx.alert_total(token),
            "pass": bool(recovered_services) and len(alarms) == 1
                    and drained["lag"] == 0 and pending and all(value == 0 for value in pending),
        }
    finally:
        if stopped or not ctx.container_running("socp-postgres"):
            ctx.docker_container("start", "socp-postgres")


def scenario_opensearch_outage(ctx, token):
    """Prove Detection is independent from OpenSearch and indexing recovers."""
    run_id = ctx.run_token("opensearch-outage")
    alert_host = f"chaos-os-alert-{run_id}"
    recovery_host = f"chaos-os-recovery-{run_id}"
    stopped = False
    try:
        ctx.docker_container("stop", "socp-opensearch")
        stopped = True
        ctx.wait_for(lambda: not ctx.container_running("socp-opensearch"), timeout=30, interval=1)
        accepted = ctx.ingest(token, [{
            "eventId": f"chaos-os-alert-{run_id}",
            "source": "auth",
            "host": alert_host,
            "severity": "HIGH",
            "message": "sudo: opensearch outage detection probe",
        }])

        def matching():
            values = [item for item in ctx.list_alerts(token)
                      if item.get("entity") == alert_host and item.get("ruleId") == "AUTH-PRIVESC"]
            return values if len(values) == 1 else None

        alarms = ctx.wait_for(matching, timeout=180, interval=2) or []
        # The API can still durably accept into PostgreSQL/outbox while the
        # asynchronous indexer is unavailable. Readiness therefore stays UP;
        # aggregate health must still expose the downstream degradation.
        alive_status, _ = ctx.request(ctx.health_url("search-config") + "/liveness",
                                  headers=ctx.auth_headers(token), timeout=4)
        readiness_status, _ = ctx.request(ctx.health_url("search-config") + "/readiness",
                                      headers=ctx.auth_headers(token), timeout=4)
        aggregate_status, _ = ctx.request(ctx.health_url("search-config"),
                                      headers=ctx.auth_headers(token), timeout=4)
        search_alive = alive_status == 200
        ctx.docker_container("start", "socp-opensearch")
        stopped = False
        # The local OpenSearch endpoint can require TLS/basic authentication;
        # Docker health is the transport-readiness check, while the indexed
        # recovery event below is the functional oracle.
        os_ready = ctx.wait_for(
            lambda: ctx.container_healthy("socp-opensearch"), timeout=120, interval=3)
        recovery = ctx.ingest(token, [{
            "eventId": f"chaos-os-recovery-{run_id}",
            "source": "system",
            "host": recovery_host,
            "severity": "INFO",
            "message": "opensearch recovery probe",
        }])

        def searchable():
            query = urllib.parse.quote(f"host={recovery_host}", safe="")
            status, body = ctx.request(
                ctx.GATEWAY_URL + "/search-config/api/v1/search?q=" + query,
                headers=ctx.auth_headers(token), timeout=10)
            data = ctx.unwrap(body)
            events = data.get("events", []) if isinstance(data, dict) else []
            return any(item.get("host") == recovery_host for item in events)

        indexed_after_recovery = ctx.wait_for(searchable, timeout=120, interval=3)
        return {
            "acceptedWhileDown": accepted,
            "matchingAlertsWhileDown": len(alarms),
            "searchConfigAliveWhileDown": search_alive,
            "searchConfigReadinessStatusWhileDown": readiness_status,
            "searchConfigAggregateStatusWhileDown": aggregate_status,
            "openSearchRecovered": bool(os_ready),
            "acceptedAfterRecovery": recovery,
            "recoveryEventIndexed": bool(indexed_after_recovery),
            "pass": len(alarms) == 1 and search_alive and readiness_status == 200
                    and aggregate_status == 503 and bool(os_ready)
                    and bool(indexed_after_recovery),
        }
    finally:
        if stopped or not ctx.container_running("socp-opensearch"):
            ctx.docker_container("start", "socp-opensearch")


def scenario_detection_outbox_replay(ctx, token):
    """Simulate publish success followed by a crash before the durable ACK."""
    run_id = ctx.run_token("detection-outbox-replay")
    host = f"chaos-outbox-{run_id}"
    ingest_result = ctx.ingest(token, [{
        "eventId": f"chaos-outbox-{run_id}",
        "source": "auth",
        "host": host,
        "severity": "CRITICAL",
        "message": "sudo: detection outbox replay probe",
    }])

    def matching():
        values = [item for item in ctx.list_alerts(token)
                  if item.get("entity") == host and item.get("ruleId") == "AUTH-PRIVESC"]
        return values if len(values) == 1 else None

    initial = ctx.wait_for(matching, timeout=180, interval=2) or []
    if len(initial) != 1 or not initial[0].get("sourceAlertId"):
        raise RuntimeError("outbox replay probe did not create its initial durable alert")
    source_alert_id = initial[0]["sourceAlertId"]
    changed = ctx.psql_scalar(
        "detect",
        "with replayed as (update t_detection_alert_outbox set status='PENDING', attempts=0, "
        "next_attempt_at=now(), delivered_at=null, published_at=null, last_error=null "
        f"where alert_id='{source_alert_id}' returning alert_id) select alert_id from replayed")
    if changed != source_alert_id:
        raise RuntimeError(f"unable to rewind Detection outbox row {source_alert_id}: {changed}")

    published = ctx.wait_for(
        lambda: ctx.psql_scalar(
            "detect",
            f"select status from t_detection_alert_outbox where alert_id='{source_alert_id}'") == "PUBLISHED",
        timeout=120, interval=2)
    replayed = [item for item in ctx.list_alerts(token)
                if item.get("sourceAlertId") == source_alert_id]
    return {
        "accepted": ingest_result,
        "sourceAlertId": source_alert_id,
        "rewoundRow": changed,
        "statusAfterReplay": ctx.psql_scalar(
            "detect", f"select status from t_detection_alert_outbox where alert_id='{source_alert_id}'"),
        "matchingAlerts": len(replayed),
        "pass": bool(published) and len(replayed) == 1,
    }
