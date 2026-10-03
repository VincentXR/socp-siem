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

def scenario_multi_instance(ctx, token, count, rebalance_cycles=1):
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

    source_baseline = ctx.wait_for(
        lambda: (snapshot if (snapshot := ctx.kafka_snapshot(ctx.CANONICAL_TOPIC, ctx.ROUTER_GROUP))
                 and snapshot["lag"] == 0 else None),
        timeout=240, interval=3)
    if source_baseline is None:
        raise RuntimeError("canonical router group did not drain before multi-instance oracle")

    baseline = ctx.wait_for(
        lambda: (snapshot if (snapshot := ctx.kafka_snapshot()) and snapshot["lag"] == 0 else None),
        timeout=240, interval=3)
    if not baseline or baseline["partitions"] != 6:
        raise RuntimeError(
            f"multi_instance requires exactly 6 routed Kafka partitions; got {baseline}")
    if not all(ctx.wait_for(lambda url=url: ctx.direct_instance_up(url, token), timeout=30, interval=1)
               for url in urls):
        raise RuntimeError(f"not all Detection instances are healthy: {urls}")

    def assignments():
        values = [ctx.direct_instance_stats(url, token) for url in urls]
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

    initial = ctx.wait_for(assignments, timeout=90, interval=2)
    if initial is None:
        raise RuntimeError("Detection instances did not obtain disjoint full partition ownership")

    run_id = ctx.run_token("multi-instance")
    dataset = ctx.DATASET_SPEC.get("multiInstance", {})
    events_per_alert = int(dataset.get("eventsPerAlert", 5))
    divisor = max(1, int(dataset.get("groupsPerBatchDivisor", events_per_alert)))
    group_count = max(1, count // divisor)
    default_tenant = str(
        dataset.get("tenantId") or os.environ.get("PIPELINE_TENANT_ID", "default"))
    digest = hashlib.sha256(
        f"{ctx.DATASET_SPEC['seed']}:{ctx.RUN_NAMESPACE}:multi-instance".encode("utf-8")
    ).digest()
    run_octets = (digest[0], digest[1])

    def threshold_batch(prefix, ip_start):
        events = []
        expected = []
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
            expected.append(ctx.expected_alert_id(
                dataset.get("ruleId", "LATERAL-RDP"), src_ip, ids, default_tenant))
        return events, expected

    events, expected_initial = threshold_batch("initial", 220)

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
    expected_cross = ctx.expected_ordered_alert_id(
        "CORR-FAIL-SUDO", cross_user, cross_ids, default_tenant)
    expected_initial.append(expected_cross)

    before = ctx.alert_total(token) or 0
    ingest_result = ctx.ingest(token, events)
    source_event_ids = {item["eventId"] for item in events}

    # CorrelationRule advances in processing order. The two canonical keys
    # intentionally differ, so Kafka cannot guarantee their relative arrival.
    # Establish the first user-dimension step durably before sending the next;
    # this remains a cross-dimension routing proof, not an event-time reorder test.
    quoted_cross_id = "'" + cross_ids[0].replace("'", "''") + "'"
    quoted_tenant = "'" + default_tenant.replace("'", "''") + "'"
    first_step = ctx.wait_for(lambda: ctx.psql_scalar(
        "detect", "select count(*) from t_detection_event "
        f"where tenant_id={quoted_tenant} and source_event_id={quoted_cross_id} "
        "and status='COMPLETED' and routing_version='detection-routing-v2' "
        "and fields_json::jsonb->>'detection_delivery_dimension'='user'") == "1",
        timeout=120, interval=2)
    if not first_step:
        raise RuntimeError("first cross-dimension correlation step did not complete on its user delivery")
    cross_followup = ctx.ingest(token, [cross_events[1]])
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
        ctx.publish_detection_event({
            "eventId": isolation_ids[0],
            "tenantId": tenant_a,
            "source": "auth",
            "host": f"iso-auth-{run_id}",
            "message": "Failed password isolation probe",
            "src_ip": "192.0.2.41",
            "user": isolated_user,
        }, target_topic=ctx.CANONICAL_TOPIC),
        ctx.publish_detection_event({
            "eventId": isolation_ids[1],
            "tenantId": tenant_b,
            "source": "auditd",
            "host": f"iso-audit-{run_id}",
            "message": f"sudo: {isolated_user} executed /bin/true",
            "src_ip": "192.0.2.42",
            "user": isolated_user,
        }, target_topic=ctx.CANONICAL_TOPIC),
    ]
    source_event_ids.update(isolation_ids)
    forbidden_cross_tenant = {
        ctx.expected_ordered_alert_id("CORR-FAIL-SUDO", isolated_user, isolation_ids, tenant_a),
        ctx.expected_ordered_alert_id("CORR-FAIL-SUDO", isolated_user, isolation_ids, tenant_b),
    }

    def oracle_alerts():
        return ctx.alerts_triggered_by(
            ctx.list_alerts(token),
            source_event_ids,
            {dataset.get("ruleId", "LATERAL-RDP"), "CORR-FAIL-SUDO"})

    def observed_oracle(expected_ids):
        values = oracle_alerts()
        actual = {item.get("sourceAlertId") for item in values if item.get("sourceAlertId")}
        return values if actual == set(expected_ids) else None

    expected_all = list(expected_initial)
    matching_all = ctx.wait_for(
        lambda: observed_oracle(expected_all), timeout=180, interval=2) or oracle_alerts()

    # Stop/restart one instance repeatedly. Survivors must jointly own a
    # disjoint, complete six-partition assignment during every outage.
    stopped = False
    cycle_results = []
    try:
        def remaining_assignment():
            survivor_urls = urls[1:]
            if not all(ctx.direct_instance_up(url, token) for url in survivor_urls):
                return None
            stats = [ctx.direct_instance_stats(url, token) for url in survivor_urls]
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
            ctx.control("stop-service", "detect-web")
            stopped = ctx.wait_for(
                lambda: not ctx.direct_instance_up(urls[0], token), timeout=30, interval=1)
            if not stopped:
                raise RuntimeError(
                    f"canonical Detection instance did not stop for rebalance cycle {cycle + 1}")

            after_stop = ctx.wait_for(remaining_assignment, timeout=90, interval=2)
            after_stop_partitions = (after_stop or {}).get("assignedPartitions", [])
            rebalance_ok = sum(len(item) for item in after_stop_partitions) == baseline["partitions"]

            post_events, expected_post = threshold_batch(
                f"post-rebalance-{cycle + 1}", 20 + cycle * 20)
            post_ingest = ctx.ingest(token, post_events)
            source_event_ids.update(item["eventId"] for item in post_events)
            expected_all.extend(expected_post)

            matching_all = ctx.wait_for(
                lambda: observed_oracle(expected_all), timeout=180, interval=2) or oracle_alerts()

            ctx.control("start-service", "detect-web")
            stopped = False
            recovered = ctx.wait_for(assignments, timeout=120, interval=2)
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
        source_after = ctx.wait_for(
            lambda: (snapshot if (snapshot := ctx.kafka_snapshot(ctx.CANONICAL_TOPIC, ctx.ROUTER_GROUP))
                     and snapshot["lag"] == 0 else None),
            timeout=240, interval=3) or ctx.kafka_snapshot(ctx.CANONICAL_TOPIC, ctx.ROUTER_GROUP)
        final_kafka = ctx.wait_for(
            lambda: (snapshot if (snapshot := ctx.kafka_snapshot()) and snapshot["lag"] == 0 else None),
            timeout=240, interval=3) or ctx.kafka_snapshot()

        matching_all = ctx.wait_for(
            lambda: observed_oracle(expected_all), timeout=120, interval=2) or oracle_alerts()
        expected_ids = set(expected_all)
        actual_ids = {item.get("sourceAlertId") for item in matching_all
                      if item.get("sourceAlertId")}
        duplicate_count = max(0, len(matching_all) - len(actual_ids))

        forbidden_sql = ",".join(
            "'" + value.replace("'", "''") + "'" for value in sorted(forbidden_cross_tenant))
        forbidden_count = int(ctx.psql_scalar(
            "alert",
            f"select count(*) from t_alarm where source_alert_id in ({forbidden_sql})") or 0)

        quoted_sources = ",".join(
            "'" + value.replace("'", "''") + "'" for value in sorted(source_event_ids))
        route_where = f"source_event_id in ({quoted_sources})"
        route_rows = int(ctx.psql_scalar(
            "detect", f"select count(*) from t_detection_route_outbox where {route_where}") or 0)
        route_distinct = int(ctx.psql_scalar(
            "detect", f"select count(distinct delivery_id) from t_detection_route_outbox where {route_where}") or 0)
        route_unfinished = int(ctx.psql_scalar(
            "detect", f"select count(*) from t_detection_route_outbox where {route_where} and status <> 'PUBLISHED'") or 0)
        route_missing_position = int(ctx.psql_scalar(
            "detect", f"select count(*) from t_detection_route_outbox where {route_where} "
            "and status='PUBLISHED' and (source_topic is null or source_partition is null "
            "or source_offset is null or delivery_topic is null or delivery_partition is null "
            "or delivery_offset is null)") or 0)
        max_fan_out = int(ctx.psql_scalar(
            "detect", "select coalesce(max(c),0) from (select source_event_id,count(*) c "
            f"from t_detection_route_outbox where {route_where} group by source_event_id) routed") or 0)

        source_receipt_rows = int(ctx.psql_scalar(
            "detect", f"select count(*) from t_detection_route_source where {route_where}") or 0)
        source_receipt_positions = int(ctx.psql_scalar(
            "detect", f"select count(distinct source_topic || ':' || source_partition::text || ':' || source_offset::text) "
            f"from t_detection_route_source where {route_where}") or 0)

        journal_rows = int(ctx.psql_scalar(
            "detect", f"select count(*) from t_detection_event where {route_where}") or 0)
        journal_distinct = int(ctx.psql_scalar(
            "detect", f"select count(distinct delivery_id) from t_detection_event where {route_where}") or 0)
        journal_sources = int(ctx.psql_scalar(
            "detect", f"select count(distinct source_event_id) from t_detection_event where {route_where}") or 0)
        journal_untraceable = int(ctx.psql_scalar(
            "detect", f"select count(*) from t_detection_event where {route_where} "
            "and (source_topic is null or source_partition is null or source_offset is null "
            "or delivery_topic is null or delivery_partition is null or delivery_offset is null)") or 0)

        # Source coverage alone can hide an entire execution class: a source's
        # stateful copy may complete while its stateless copy is rejected.
        # Reconcile every tenant-scoped published delivery against completion.
        route_missing_completed_journal = int(ctx.psql_scalar(
            "detect", "select count(*) from t_detection_route_outbox r "
            "left join t_detection_event j on j.tenant_id=r.tenant_id "
            "and j.delivery_id=r.delivery_id "
            f"where r.source_event_id in ({quoted_sources}) "
            "and (j.delivery_id is null or j.status <> 'COMPLETED')") or 0)
        stateless_routes = int(ctx.psql_scalar(
            "detect", f"select count(*) from t_detection_route_outbox where {route_where} "
            "and route_kind='STATELESS'") or 0)
        stateless_completed = int(ctx.psql_scalar(
            "detect", "select count(*) from t_detection_route_outbox r "
            "join t_detection_event j on j.tenant_id=r.tenant_id "
            "and j.delivery_id=r.delivery_id "
            f"where r.source_event_id in ({quoted_sources}) "
            "and r.route_kind='STATELESS' and j.status='COMPLETED'") or 0)

        instance_stats = [ctx.direct_instance_stats(url, token) for url in urls]
        pending_values = [item.get("pendingEvents") for item in instance_stats
                          if isinstance(item, dict)]

        delivery = None
        if os.environ.get("SOCP_REQUIRE_DOWNSTREAM_DRAIN", "false").lower() == "true":
            delivery = ctx.wait_for(
                lambda: (snapshot if (snapshot := ctx.delivery_evidence(expected_ids))["rows"] >= len(expected_ids) * 4
                         and snapshot["pendingOrProcessing"] == 0
                         and snapshot["undelivered"] == 0 else None),
                timeout=240, interval=3) or ctx.delivery_evidence(expected_ids)
        else:
            try:
                delivery = ctx.delivery_evidence(expected_ids)
            except RuntimeError as unavailable:
                delivery = {"unavailable": str(unavailable)}

        ck_logical = ctx.clickhouse_scalar(
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
        if stopped and not ctx.direct_instance_up(urls[0], token):
            ctx.control("start-service", "detect-web")
