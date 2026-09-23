#!/usr/bin/env python3
"""Inspect, export and redrive a bounded Kafka DLQ batch without rewriting bytes.

Use --export-file to capture source records, --input-file for offline review,
and --output-file to retain the selected source records for a later replay.
Files use socp-dlq-batch/v1 JSON with Base64 key/value/header bytes. Console TSV
is deliberately unsupported because it cannot distinguish nulls and line breaks.

Live transport requires Java 21 and Kafka client jars (--kafka-classpath or
--kafka-bin). Inspection assigns partitions directly and never commits offsets.
Detection journal and routed-header-loss guards remain mandatory.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import hashlib
from itertools import islice
import json
import os
from pathlib import Path
import sys
from typing import Iterable, Sequence

from dlq_transport import (
    Record, TransportError, MAX_COUNT, bridge_command, decode_wire, encode_wire,
    read_batch, run_process, strict_json, topic_name, validate_records, write_batch,
)

MAIN_TOPICS = (
    "socp-events", "socp-detection-routed-v2", "socp-alarm-events",
    "socp-alarm-original", "socp-rule-changes", "socp-audit",
)
DETECTION_SKIP_TOPICS = {"socp-events", "socp-detection-routed-v2"}
HEADER_LOSS_TOPICS = {"socp-detection-routed-v2"}
DLQ_SUFFIX = "-dlq"
DEFAULT_LIMIT = 100
MAX_LIMIT = MAX_COUNT
ENVELOPE_FIELDS = {
    "originalTopic", "partition", "offset", "key", "eventId", "tenant",
    "schemaVersion", "reasonCode", "reason", "originalPayload", "failedAt",
}


def bounded_integer(low: int, high: int):
    def parse(value: str) -> int:
        try:
            number = int(value)
        except ValueError as error:
            raise argparse.ArgumentTypeError("expected an integer") from error
        if not low <= number <= high:
            raise argparse.ArgumentTypeError(f"value must be between {low} and {high}")
        return number
    return parse


batch_limit = bounded_integer(1, MAX_LIMIT)


def normalise_dlq_topic(topic: str) -> str:
    return topic_name(topic if topic.endswith(DLQ_SUFFIX) else f"{topic}{DLQ_SUFFIX}")


def main_topic_for(dlq_topic: str) -> str:
    if not dlq_topic.endswith(DLQ_SUFFIX):
        raise ValueError("expected a dead-letter topic ending in -dlq")
    return topic_name(dlq_topic[:-len(DLQ_SUFFIX)])


@dataclass
class Action:
    source: Record
    target_topic: str
    key: bytes | None
    payload: bytes | None
    target_partition: int = -1
    original_offset: int | None = None
    envelope_tenant: str = ""
    record_shape: str = "raw"
    reason_code: str = ""
    skip: bool = False
    skip_reason: str = ""
    deduped: bool = False
    notes: list[str] = field(default_factory=list)

    def output_record(self) -> Record:
        # DLQ coordinates are provenance, not the original raw event's partition.
        # An indexer envelope does carry the original partition. Kafka record
        # timestamps are new; application event-time bytes remain unchanged.
        return Record(self.target_topic, self.key, self.payload, self.target_partition,
                      -1, -1, self.source.headers)


def classify(record: Record, target: str) -> Action:
    action = Action(record, target, record.key, record.value)
    if record.value is None:
        action.record_shape = "tombstone"
        return action
    try:
        parsed = strict_json(record.value.decode("utf-8"))
    except (ValueError, UnicodeError, RecursionError):
        action.record_shape = "unparseable"
        return action
    if not isinstance(parsed, dict) or not {"originalTopic", "originalPayload"} <= parsed.keys():
        return action
    # An indexer envelope has a complete, documented shape. Partial/ambiguous
    # envelopes cannot be safely unwrapped or repaired by inventing a key.
    if not ENVELOPE_FIELDS <= parsed.keys():
        action.skip = True
        action.skip_reason = "incomplete-indexer-envelope"
        return action
    action.record_shape = "envelope"
    if parsed["originalTopic"] != target:
        action.skip = True
        action.skip_reason = "original-topic-mismatch"
        return action
    key, payload = parsed["key"], parsed["originalPayload"]
    partition, offset = parsed["partition"], parsed["offset"]
    if ((key is not None and not isinstance(key, str))
            or (payload is not None and not isinstance(payload, str))
            or type(partition) is not int or not 0 <= partition <= 2**31 - 1
            or type(offset) is not int or not 0 <= offset <= 2**63 - 1
            or not isinstance(parsed["reasonCode"], str)
            or (parsed["tenant"] is not None and not isinstance(parsed["tenant"], str))):
        action.skip = True
        action.skip_reason = "invalid-indexer-envelope"
        return action
    try:
        action.key = None if key is None else key.encode("utf-8")
        action.payload = None if payload is None else payload.encode("utf-8")
    except UnicodeError:
        action.skip = True
        action.skip_reason = "invalid-indexer-envelope"
        return action
    action.target_partition = partition
    action.original_offset = offset
    action.reason_code = parsed["reasonCode"]
    action.envelope_tenant = json.dumps(parsed["tenant"], ensure_ascii=True)
    return action


def plan_actions(records: Iterable[Record], dlq_topic: str, dedup: bool = True,
                 include_tombstones: bool = False) -> list[Action]:
    target = main_topic_for(dlq_topic)
    rows = list(islice(records, MAX_LIMIT + 1))
    validate_records(rows, dlq_topic)
    actions = []
    seen = set()
    for record in rows:
        action = classify(record, target)
        if target in HEADER_LOSS_TOPICS:
            action.skip = True
            action.skip_reason = "routing-header-not-persisted"
        elif not action.skip and action.payload is None and not include_tombstones:
            action.skip = True
            action.skip_reason = "tombstone-requires-explicit-opt-in"
        if not action.skip and dedup:
            origin = ((action.target_partition, action.original_offset) if action.record_shape == "envelope"
                      else (record.partition, record.offset))
            signature = (target, origin, action.key, action.payload,
                         record.headers, action.envelope_tenant)
            # A missing key supplies no stable deduplication identity.
            if action.key is not None and min(origin) >= 0 and signature in seen:
                action.skip = True
                action.deduped = True
                action.skip_reason = "duplicate-of-earlier-in-batch"
            seen.add(signature)
        if not action.skip and target in DETECTION_SKIP_TOPICS:
            action.notes.append("Detection's DEAD_LETTERED journal still blocks this identity; broker replay alone does not recover detection.")
        actions.append(action)
    validate_records([action.output_record() for action in actions if not action.skip])
    return actions


def broker_records(args, topic: str) -> list[Record]:
    command = bridge_command(args.java, args.kafka_classpath, args.kafka_bin)
    command += ["consume", args.bootstrap, topic, str(args.limit), str(args.timeout_ms),
                str(args.consumer_config) if args.consumer_config else "-",
                str(args.partition if args.partition is not None else -1),
                str(args.offset if args.offset is not None else -1)]
    data = run_process(command, None, args.timeout_ms / 1000 + 30)
    records = decode_wire(data)
    if len(records) > args.limit:
        raise TransportError("transport returned more records than requested")
    validate_records(records, topic)
    return records


def produce_actions(actions: Sequence[Action], args, target: str) -> None:
    records = [action.output_record() for action in actions if not action.skip]
    validate_records(records, target)
    command = bridge_command(args.java, args.kafka_classpath, args.kafka_bin)
    command += ["produce", args.bootstrap, target, str(args.limit), str(args.timeout_ms),
                str(args.producer_config) if args.producer_config else "-", "-1", "-1"]
    try:
        output = run_process(command, encode_wire(records), args.timeout_ms / 1000 + 30, output_limit=1024)
        if output != f"ACK\t{len(records)}\n".encode("ascii"):
            raise TransportError("invalid producer acknowledgement")
    except TransportError as failure:
        raise TransportError(f"{failure}; some records may already be acknowledged. Inspect the destination before retrying.") from failure


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--topic", required=True, help="DLQ topic or its main topic")
    parser.add_argument("--bootstrap", default=os.environ.get("SOCP_KAFKA_BOOTSTRAP", "localhost:9092"))
    parser.add_argument("--kafka-bin", default=os.environ.get("KAFKA_BIN_DIR"), help="Kafka installation bin directory (uses sibling libs)")
    parser.add_argument("--kafka-classpath", default=os.environ.get("SOCP_KAFKA_CLASSPATH"), help="Java classpath containing Kafka client dependencies")
    parser.add_argument("--java", default=None, help="Java 21 executable")
    parser.add_argument("--consumer-config", type=Path, help="Kafka client properties, including TLS/SASL; no offset commits")
    parser.add_argument("--producer-config", type=Path, help="Kafka producer security properties; reliable byte transport settings are enforced")
    parser.add_argument("--limit", type=batch_limit, default=DEFAULT_LIMIT, help="source records before dedup (default 100, max 10000)")
    parser.add_argument("--timeout-ms", type=bounded_integer(1000, 120000), default=10000, help="Kafka operation deadline (default 10000, max 120000)")
    parser.add_argument("--partition", type=bounded_integer(0, 2**31 - 1), help="inspect only this source partition; requires --offset")
    parser.add_argument("--offset", type=bounded_integer(0, 2**63 - 1), help="inclusive source offset; requires --partition")
    parser.add_argument("--no-dedup", action="store_true", help="retain byte-identical records with the same non-null key and metadata")
    parser.add_argument("--include-tombstones", action="store_true", help="explicitly replay null values, which may delete keys on compacted topics")
    parser.add_argument("--force", action="store_true", help="override only the detection terminal-journal guard, after authorized recovery")
    parser.add_argument("--dry-run", action="store_true", help="inspect; never produce")
    parser.add_argument("--input-file", type=Path, help="read a socp-dlq-batch/v1 JSON export; console TSV is unsupported")
    outputs = parser.add_mutually_exclusive_group()
    outputs.add_argument("--export-file", type=Path, help="capture all inspected source records without replay, even when replay is blocked")
    outputs.add_argument("--output-file", type=Path, help="export selected original DLQ records for a later guarded replay")
    return parser


def execute(args) -> int:
    dlq_topic = normalise_dlq_topic(args.topic)
    target = main_topic_for(dlq_topic)
    if args.input_file:
        records = read_batch(args.input_file, dlq_topic)[:args.limit]
    else:
        records = broker_records(args, dlq_topic)
    if args.export_file:
        write_batch(args.export_file, records, dlq_topic)
        print(f"exported {len(records)} source record(s); nothing produced", file=sys.stderr)
        return 0
    actions = plan_actions(records, dlq_topic, not args.no_dedup, args.include_tombstones)
    for action in actions:
        key = "null" if action.key is None else repr(action.key[:80])
        digest = "null" if action.payload is None else hashlib.sha256(action.payload).hexdigest()
        print(f"{'SKIP' if action.skip else 'SEND'}\ttarget={target}\tshape={action.record_shape}"
              f"\tkey={key}\tbytes={len(action.payload) if action.payload is not None else 'null'}\tsha256={digest}"
              f"\tsource={action.source.partition}:{action.source.offset}"
              f"\treason={json.dumps(action.reason_code, ensure_ascii=True)}"
              f"\tskip_reason={action.skip_reason}")
        for note in action.notes:
            print(f"       note: {note}")
    sendable = [action for action in actions if not action.skip]
    print(f"--- plan: {len(actions)} records; send={len(sendable)} skip={len(actions)-len(sendable)} "
          f"(deduped={sum(action.deduped for action in actions)}) ---", file=sys.stderr)
    blocked = [action for action in actions if action.skip_reason in {
        "routing-header-not-persisted", "original-topic-mismatch", "incomplete-indexer-envelope", "invalid-indexer-envelope"}]
    if blocked:
        print("[BLOCKED] Missing/invalid original metadata or routed headers; nothing produced. --force cannot override this.", file=sys.stderr)
        return 3
    if sendable and target in DETECTION_SKIP_TOPICS and not args.force:
        print("[BLOCKED] Detection terminal journal requires authorized recovery first; see docs/operations/dlq-replay.md. Nothing produced.", file=sys.stderr)
        return 3
    if args.output_file:
        write_batch(args.output_file, [action.source for action in sendable], dlq_topic)
        print(f"wrote {len(sendable)} selected source record(s); nothing produced", file=sys.stderr)
        return 0
    if args.dry_run:
        print("[dry-run] nothing produced", file=sys.stderr)
        return 0
    if not sendable:
        print("nothing to replay", file=sys.stderr)
        return 0
    produce_actions(sendable, args, target)
    print(f"broker acknowledged {len(sendable)} record(s) to {target}; downstream recovery still requires verification", file=sys.stderr)
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if (args.partition is None) != (args.offset is None):
        parser.error("--partition and --offset must be supplied together")
    if args.input_file and args.partition is not None:
        parser.error("partition/offset selectors apply only to broker input")
    try:
        return execute(args)
    except (OSError, ValueError, TransportError, RecursionError) as failure:
        # These are tool-owned validation/process errors; Kafka exception bodies
        # are never propagated by the bridge.
        print(f"[FAIL] {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
