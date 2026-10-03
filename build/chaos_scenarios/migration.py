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

def scenario_routed_migration(ctx, token, count):
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
    if ctx.TOPIC != ctx.ROUTED_TOPIC:
        raise RuntimeError("routed migration requires the cluster to consume the routed topic")
    urls = [item.strip().rstrip("/")
            for item in os.environ.get("DETECTION_INSTANCE_URLS", "").split(",")
            if item.strip()]
    if len(urls) != 3:
        raise RuntimeError("routed migration requires three Detection instance URLs")
    instance = urls[0]

    def drained():
        source = ctx.kafka_snapshot(ctx.CANONICAL_TOPIC, ctx.ROUTER_GROUP)
        routed = ctx.kafka_snapshot()
        return (source["lag"] == 0 and routed["lag"] == 0) or None

    if ctx.wait_for(drained, timeout=240, interval=3) is None:
        raise RuntimeError("routed migration requires a fully drained baseline")

    dataset = ctx.DATASET_SPEC.get("multiInstance", {})
    events_per_alert = int(dataset.get("eventsPerAlert", 5))
    group_count = max(1, count // max(1, int(dataset.get("groupsPerBatchDivisor", events_per_alert))))
    digest = hashlib.sha256(f"{ctx.DATASET_SPEC['seed']}:{ctx.RUN_NAMESPACE}:routed-migration".encode()).digest()
    run_id = ctx.run_token("routed-migration")

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
            expected.append(ctx.expected_alert_id(
                dataset.get("ruleId", "LATERAL-RDP"), src_ip, ids, "default"))
            entities.add(src_ip)
        return events, expected, entities

    def observed(entities_of_interest, expected_ids):
        values = []
        for item in ctx.list_alerts(token):
            if item.get("ruleId") == dataset.get("ruleId", "LATERAL-RDP") \
                    and item.get("entity") in entities_of_interest:
                values.append(item)
        actual = {item.get("sourceAlertId") for item in values if item.get("sourceAlertId")}
        return values if actual == set(expected_ids) else None

    def receipts(source_ids):
        quoted = ",".join("'" + value.replace("'", "''") + "'" for value in sorted(source_ids))
        where = f"source_event_id in ({quoted})"
        return (
            int(ctx.psql_scalar("detect", f"select count(*) from t_detection_route_source where {where}") or 0),
            int(ctx.psql_scalar("detect", f"select count(*) from t_detection_route_outbox where {where}") or 0),
            int(ctx.psql_scalar("detect",
                            f"select count(distinct delivery_id) from t_detection_route_outbox where {where}") or 0),
            ctx.psql_scalar("detect", "select coalesce(string_agg(delivery_id, ',' order by delivery_id), '') "
                        f"from t_detection_route_outbox where {where}"),
        )

    # -- phase 1: duplicate canonical deliveries --------------------------------
    events_a, expected_a, entities_a = batch("dup", 40)
    ctx.ingest(token, events_a)
    matched_a = ctx.wait_for(lambda: observed(entities_a, expected_a), timeout=180, interval=2)
    if matched_a is None:
        raise RuntimeError("routed migration phase 1 baseline alerts never materialized")
    receipts_before = receipts({item["eventId"] for item in events_a})
    for item in events_a:
        ctx.publish_detection_event(item, target_topic=ctx.CANONICAL_TOPIC)
    if ctx.wait_for(drained, timeout=240, interval=3) is None:
        raise RuntimeError("router did not drain duplicate canonical deliveries")
    receipts_after = receipts({item["eventId"] for item in events_a})
    settled = ctx.wait_for(lambda: observed(entities_a, expected_a), timeout=30, interval=2)
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
    status, body = ctx.request(f"{instance}/detect-web/api/v1/rules", method="POST",
                           body=json.dumps(bad_rule),
                           headers={**ctx.auth_headers(token), "Content-Type": "application/json"},
                           timeout=20)
    if status != 200:
        raise RuntimeError(f"could not create incompatible rule: {status} {body}")
    created_rule = ctx.unwrap(body)
    quoted_rule_id = "'" + bad_rule["id"].replace("'", "''") + "'"
    rule_where = f"tenant_id='default' and rule_id={quoted_rule_id}"
    stored_spec = ctx.psql_scalar("detect", f"select spec from t_rule where {rule_where}")
    if json.loads(stored_spec).get("status") != "TESTING":
        raise RuntimeError("incompatible rule did not begin in the review queue")
    # Exercise the topology conflict after the separate activation permission
    # check; the analyst session used for normal queries cannot activate rules.
    activation_token = ctx.login_token(ctx.GATEWAY_URL, os.environ.get("RULE_VERIFY_USERNAME", "admin"),
                                   os.environ.get("RULE_VERIFY_PASSWORD", "admin123"))
    status, body = ctx.request(f"{instance}/detect-web/api/v1/rules/{bad_rule['id']}/activate",
                           method="POST", headers={**ctx.auth_headers(activation_token),
                           "If-Match": '"' + created_rule["revisionToken"] + '"'}, timeout=20)
    if status != 409:
        raise RuntimeError(f"incompatible activation was not rejected: {status} {body}")
    if ctx.psql_scalar("detect", f"select spec from t_rule where {rule_where}") != stored_spec:
        raise RuntimeError("rejected activation changed the persisted rule")

    # This scenario already requires disposable Compose infrastructure. Model
    # a corrupt restore/administrative write so the runtime fail-closed check
    # remains covered even though normal API writes now prevent this state.
    quoted_spec = "'" + stored_spec.replace("'", "''") + "'"
    injected = ctx.psql_scalar(
        "detect", "with injected as (update t_rule set "
        "spec=(spec::jsonb || '{\"status\":\"ACTIVE\",\"enabled\":true,"
        "\"routingField\":\"service_name\"}'::jsonb)::text "
        f"where {rule_where} and spec={quoted_spec} returning rule_id) "
        "select rule_id from injected")
    if injected != bad_rule["id"]:
        raise RuntimeError("could not inject the isolated persisted-rule fault")

    def plan_unsupported():
        code, plan_body = ctx.request(f"{instance}/detect-web/api/v1/routing-plan",
                                  headers=ctx.auth_headers(token), timeout=10)
        plan = ctx.unwrap(plan_body) if code == 200 else None
        if not isinstance(plan, dict) or plan.get("status") != "UNSUPPORTED":
            return None
        reasons = [rule for rule in plan.get("rules", [])
                   if rule.get("ruleId") == bad_rule["id"] and rule.get("status") == "UNSUPPORTED"]
        return plan if reasons else None

    try:
        plan = ctx.wait_for(plan_unsupported, timeout=60, interval=2)
        if plan is None:
            raise RuntimeError("routing plan never surfaced the incompatible ACTIVE rule as UNSUPPORTED")

        events_b, expected_b, entities_b = batch("blocked", 120)
        ctx.ingest(token, events_b)
        time.sleep(20)
        blocked_alerts = observed(entities_b, expected_b)
        blocked_lag = ctx.kafka_snapshot(ctx.CANONICAL_TOPIC, ctx.ROUTER_GROUP)
        if blocked_alerts is not None:
            raise RuntimeError("router kept delivering as if healthy despite the unsupported rule")
        if blocked_lag["lag"] == 0:
            raise RuntimeError("router committed canonical offsets despite failing closed")

    finally:
        # Restore the deliberately corrupted fixture before conditional deletion.
        restored = ctx.psql_scalar(
            "detect", f"with restored as (update t_rule set spec={quoted_spec} "
            f"where {rule_where} returning rule_id) select rule_id from restored")
        if restored != bad_rule["id"]:
            raise RuntimeError("could not restore the isolated persisted-rule fault")
        status, body = ctx.request(f"{instance}/detect-web/api/v1/rules/{bad_rule['id']}",
                               headers=ctx.auth_headers(token), timeout=20)
        if status != 200:
            raise RuntimeError(f"could not read restored rule: {status}")
        status, _ = ctx.request(f"{instance}/detect-web/api/v1/rules/{bad_rule['id']}", method="DELETE",
                            headers={**ctx.auth_headers(token), "If-Match": '"' + ctx.unwrap(body)["revisionToken"] + '"'},
                            timeout=20)
        if status != 200:
            raise RuntimeError(f"could not delete incompatible rule: {status}")
    def plan_supported():
        code, plan_body = ctx.request(f"{instance}/detect-web/api/v1/routing-plan",
                                  headers=ctx.auth_headers(token), timeout=10)
        plan = ctx.unwrap(plan_body) if code == 200 else None
        return plan if isinstance(plan, dict) and plan.get("status") == "SUPPORTED" else None

    if ctx.wait_for(plan_supported, timeout=90, interval=3) is None:
        raise RuntimeError("routing plan did not recover after the rule removal")
    if ctx.wait_for(drained, timeout=240, interval=3) is None:
        raise RuntimeError("canonical source did not resume draining after the fix")
    deferred_alerts = ctx.wait_for(lambda: observed(entities_b, expected_b), timeout=180, interval=2)
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


def scenario_routing_rollback(ctx, token, count):
    """Rollback evidence: legacy input keeps a formal output path, never claims
    cross-dimension completeness, and the routed generation restores cleanly."""
    urls = [item.strip().rstrip("/")
            for item in os.environ.get("DETECTION_INSTANCE_URLS", "").split(",")
            if item.strip()]
    if len(urls) != 3:
        raise RuntimeError("rollback evidence requires three Detection instance URLs")
    dataset = ctx.DATASET_SPEC.get("multiInstance", {})
    events_per_alert = int(dataset.get("eventsPerAlert", 5))
    group_count = max(1, count // max(1, int(dataset.get("groupsPerBatchDivisor", events_per_alert))))
    digest = hashlib.sha256(f"{ctx.DATASET_SPEC['seed']}:{ctx.RUN_NAMESPACE}:routing-rollback".encode()).digest()
    run_id = ctx.run_token("routing-rollback")
    evidence_dir = ctx.REPO / ".cache" / "chaos" / f"rollback-{run_id}"
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
            expected.append(ctx.expected_alert_id(
                dataset.get("ruleId", "LATERAL-RDP"), src_ip, ids, "default"))
        return events, expected

    def restart_cluster(routing_mode, input_topic, publisher_enabled):
        ctx.stop_auto_detection_cluster()
        # The startup helper truncates each instance log. Preserve the previous
        # generation after shutdown, especially the failed legacy generation
        # before the mandatory cleanup restores primary mode.
        log_dir = ctx.REPO / ".cache" / "detection-cluster"
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
        result = subprocess.run([ctx.RUNNER, str(ctx.BUILD / "detection-cluster.sh"), "start"],
                                cwd=ctx.REPO, capture_output=True, text=True,
                                timeout=240, check=False, env=env)
        if result.returncode != 0:
            raise RuntimeError(f"cluster restart ({routing_mode}) failed: {result.stderr[-800:]}")
        if routing_mode == "legacy":
            # The legacy generation commits its own group directly on the
            # canonical topic; drain that instead of the router group.
            ok = ctx.wait_for(lambda: (ctx.kafka_snapshot(input_topic) or {}).get("lag") == 0,
                          timeout=240, interval=3)
        else:
            ok = ctx.wait_for(lambda: ((ctx.kafka_snapshot(ctx.CANONICAL_TOPIC, ctx.ROUTER_GROUP) or {}).get("lag") == 0
                                   and (ctx.kafka_snapshot() or {}).get("lag") == 0),
                          timeout=240, interval=3)
        if ok is None:
            raise RuntimeError(f"detection did not drain after restart in {routing_mode} mode")

    def capture_legacy_evidence(events, expected, entities):
        evidence = {"expectedAlertIds": sorted(expected), "sourceEvents": events}
        probes = {
            "kafka": lambda: ctx.kafka_snapshot(ctx.CANONICAL_TOPIC, ctx.GROUP),
            "alerts": lambda: [item for item in ctx.list_alerts(token)
                               if item.get("ruleId") == dataset.get("ruleId", "LATERAL-RDP")
                               and item.get("entity") in entities],
            "instances": lambda: [ctx.direct_instance_stats(url, token) for url in urls],
            "routingPlan": lambda: ctx.request(f"{urls[0]}/detect-web/api/v1/routing-plan",
                                            headers=ctx.auth_headers(token), timeout=10)[1],
        }
        quoted = ",".join("'" + item["eventId"].replace("'", "''") + "'" for item in events)
        probes["journal"] = lambda: json.loads(ctx.psql_scalar(
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
        restart_cluster("legacy", ctx.CANONICAL_TOPIC, "false")
        events, expected = batch("legacy", 160)
        ctx.ingest(token, events)
        entities = {item["src_ip"] for item in events}

        def legacy_alerts():
            values = [item for item in ctx.list_alerts(token)
                      if item.get("ruleId") == dataset.get("ruleId", "LATERAL-RDP")
                      and item.get("entity") in entities]
            actual = {item.get("sourceAlertId") for item in values if item.get("sourceAlertId")}
            return values if actual == set(expected) else None

        matched = ctx.wait_for(legacy_alerts, timeout=240, interval=2)
        legacy_evidence = capture_legacy_evidence(events, expected, entities)
        if matched is None:
            actual = sorted(str(item.get("sourceAlertId") or "")
                            for item in legacy_evidence.get("alerts", [])
                            if isinstance(item, dict))
            raise RuntimeError("rollback to the legacy input topic lost the formal alert path; "
                               f"expected={sorted(expected)} actual={actual}; "
                               f"evidence={evidence_dir.relative_to(ctx.REPO)}")
        code, body = ctx.request(f"{urls[0]}/detect-web/api/v1/routing-plan",
                             headers=ctx.auth_headers(token), timeout=10)
        plan = ctx.unwrap(body) if code == 200 else None
        deployment = json.dumps((plan or {}).get("deployment", {}))
        if "LEGACY_PARTIAL" not in deployment:
            raise RuntimeError(
                f"legacy rollback must report LEGACY_PARTIAL, got: {deployment[:400]}")
        restart_cluster("primary", ctx.ROUTED_TOPIC, "true")
        restored = True
        return {"legacyFormalOutputRecovered": True,
                "legacyReportsPartial": True,
                "legacyExpectedAlertIds": sorted(expected),
                "legacyActualAlertIds": sorted(item.get("sourceAlertId") for item in matched),
                "legacyEvidence": str(evidence_dir.relative_to(ctx.REPO)),
                "routedGenerationRestored": True,
                "pass": True}
    finally:
        if not restored:
            try:
                restart_cluster("primary", ctx.ROUTED_TOPIC, "true")
            except Exception:
                pass
