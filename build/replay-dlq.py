#!/usr/bin/env python3
"""Redrive records from a SOCP ``<topic>-dlq`` to the corresponding main topic.

Application consumers do not automatically read terminal DLQ records. This
tool provides a fail-closed, idempotent operator redrive.

Re-drive correctness rests on three facts, each mirrored from the durable contracts:

1. At-least-once + stable identity. The event path deduplicates on a stable
   event id (``eventId`` for search/detection, ``tenantId``+``eventId`` for the
   OpenSearch ``_id``), so re-producing the *original* payload under the *original*
   key is safe to repeat: downstream sinks drop the duplicate instead of
   double-processing. We therefore re-send ``originalPayload`` (envelope records)
   or the raw value (non-envelope records), never the DLQ envelope itself.
2. Heterogeneous DLQ records. Only ``OsIndexerConsumer`` writes a re-drivable
   structured envelope (``originalTopic``/``partition``/``offset``/``key``/
   ``eventId``/``tenant``/``schemaVersion``/``reasonCode``/``reason``/
   ``originalPayload``/``failedAt``); the other writers store the raw value with a
   thin or empty reason. The tool classifies each record and replays accordingly,
   and never fabricates a reason it cannot read.
3. Detection-path one-way journal and routed-header loss.
   ``socp-events`` and ``socp-detection-routed-v2`` feed detect-web, whose
   ``DetectionEventJournal`` treats a repeat of a ``DEAD_LETTERED`` id as a
   permanent skip (``docs/detection-state-semantics.md``). A plain re-produce to
   ``socp-events`` therefore re-indexes into OpenSearch but is *silently skipped*
   on the detection lane until an operator resets the terminal journal row (no
   admin endpoint exists -- see docs/operations/dlq-replay.md). Re-driving to
   ``socp-detection-routed-v2`` is worse: that topic's per-dimension routing key
   lives in a record *header* that the DLQ write does not persist, so a re-produced
   record would misroute. Both cases are refused unless ``--force``.

The broker interaction is delegated to the Kafka CLI (``kafka-console-consumer`` /
``kafka-console-producer``) so this tool needs no third-party client library and
matches how ``infra/init-sql/kafka/create-topics.sh`` already drives the broker.

Examples::

    # Inspect a small batch without touching the broker at all:
    python build/replay-dlq.py --topic socp-events-dlq --dry-run --limit 50

    # Replay a reviewed batch (idempotent; safe to repeat):
    python build/replay-dlq.py --topic socp-events-dlq --limit 100

    # Offline triage from a console-consumer export (tab-separated key<TAB>value):
    python build/replay-dlq.py --topic socp-events-dlq \
        --input-file /tmp/socp-events-dlq.tsv --dry-run
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator, Sequence

# Every consumer derives its dead-letter topic in code as ``topic + "-dlq"``
# (infra/init-sql/kafka/create-topics.sh). We accept either spelling and
# normalise. The name list is only used for the "unknown topic" advisory.
MAIN_TOPICS = (
    "socp-events",
    "socp-detection-routed-v2",
    "socp-alarm-events",
    "socp-alarm-original",
    "socp-rule-changes",
    "socp-audit",
)
# Consumed by detect-web, whose journal blocks reprocessing of DEAD_LETTERED ids
# and (for routed-v2) whose routing decision depends on a header the DLQ dropped.
DETECTION_SKIP_TOPICS = {"socp-events", "socp-detection-routed-v2"}
# Re-driving this main topic is unsafe outright: routing is header-derived and the
# header is not stored in the DLQ record, so a replayed record would misroute.
HEADER_LOSS_TOPICS = {"socp-detection-routed-v2"}

DLQ_SUFFIX = "-dlq"


def normalise_dlq_topic(topic: str) -> str:
    return topic if topic.endswith(DLQ_SUFFIX) else f"{topic}{DLQ_SUFFIX}"


def main_topic_for(dlq_topic: str) -> str:
    if not dlq_topic.endswith(DLQ_SUFFIX):
        raise ValueError(f"not a dead-letter topic: {dlq_topic}")
    return dlq_topic[: -len(DLQ_SUFFIX)]


@dataclass
class Action:
    target_topic: str
    key: str | None
    payload: str
    source_partition: int | None = None
    source_offset: int | None = None
    reason_code: str = ""
    # Filled by the planner; consumed by the guard / reporting.
    record_shape: str = "raw"
    skip: bool = False
    skip_reason: str = ""
    deduped: bool = False
    notes: list[str] = field(default_factory=list)


def classify(raw_key: str | None, raw_value: str) -> tuple[str, str | None, str, int | None, int | None, str]:
    """Return ``(shape, derived_key, payload, partition, offset, reason_code)``.

    ``shape`` is ``envelope`` (a structured OsIndexerConsumer record whose real
    event is under ``originalPayload``), ``raw`` (the value *is* the event), or
    ``unparseable``.
    """
    if raw_value is None:
        return "unparseable", raw_key, "", None, None, "tombstone"
    text = raw_value.strip()
    if not text:
        return "unparseable", raw_key, "", None, None, "empty"
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        # Non-JSON value: pass it through untouched. Some producers may write
        # non-JSON; we do not silently drop data during triage.
        return "unparseable", raw_key, raw_value, None, None, "not-json"

    if isinstance(parsed, dict) and "originalPayload" in parsed and "originalTopic" in parsed:
        payload = parsed.get("originalPayload")
        payload = payload if isinstance(payload, str) else json.dumps(payload, separators=(",", ":"))
        derived_key = parsed.get("eventId") or parsed.get("key") or raw_key
        partition = parsed.get("partition")
        offset = parsed.get("offset")
        reason = str(parsed.get("reasonCode") or "")
        return (
            "envelope",
            str(derived_key) if derived_key is not None else None,
            payload,
            partition if isinstance(partition, int) else None,
            offset if isinstance(offset, int) else None,
            reason,
        )

    # Raw record: the value is the event. Preserve the broker key; if it is a
    # JSON object carrying eventId, prefer that for a stable idempotency key.
    derived_key = raw_key
    if isinstance(parsed, dict):
        for candidate in ("eventId", "event_id", "id"):
            value = parsed.get(candidate)
            if value not in (None, ""):
                derived_key = str(value)
                break
    return "raw", derived_key, raw_value, None, None, ""


def plan_actions(records: Iterable[tuple[str | None, str]],
                 dlq_topic: str,
                 dedup: bool = True) -> list[Action]:
    """Turn DLQ ``(key, value)`` records into a replay plan for ``dlq_topic``.

    The planner never talks to a broker; it decides target topic, key, payload and
    whether a record must be skipped for safety. That keeps the correctness
    argument testable and reviewable.
    """
    target = main_topic_for(dlq_topic)
    actions: list[Action] = []
    seen: set[tuple[str, str | None]] = set()
    for key, value in records:
        shape, derived_key, payload, partition, offset, reason = classify(key, value)
        action = Action(
            target_topic=target,
            key=derived_key,
            payload=payload,
            source_partition=partition,
            source_offset=offset,
            reason_code=reason,
            record_shape=shape,
        )
        if shape == "unparseable" and not payload:
            action.skip = True
            action.skip_reason = "empty-or-tombstone"
            actions.append(action)
            continue
        if target in HEADER_LOSS_TOPICS:
            action.skip = True
            action.skip_reason = "routing-header-not-persisted"
            action.notes.append(
                "routed-v2 routing is header-derived and the header is not in the DLQ record")
            actions.append(action)
            continue
        if dedup:
            signature = (target, derived_key)
            if derived_key is not None and signature in seen:
                action.skip = True
                action.deduped = True
                action.skip_reason = "duplicate-of-earlier-in-batch"
                actions.append(action)
                continue
        seen.add((target, derived_key))
        if target in DETECTION_SKIP_TOPICS:
            action.notes.append(
                "detect-web journal skips repeat of DEAD_LETTERED ids; OpenSearch "
                "re-index will recover, the detection lane needs a journal terminal-row reset first")
        actions.append(action)
    return actions


# --------------------------------------------------------------------------- #
# Broker / file I/O adapters
# --------------------------------------------------------------------------- #

def _kafka_tool(name: str, kafka_bin: str | None) -> str:
    if kafka_bin:
        candidate = Path(kafka_bin) / name
        if candidate.exists() or Path(kafka_bin).is_file():
            return str(candidate if candidate.exists() else Path(kafka_bin))
    found = shutil.which(name) or shutil.which(f"{name}.sh")
    if found:
        return found
    raise SystemExit(
        f"[FAIL] {name} not found on PATH; set --kafka-bin to the Kafka bin directory")


def iter_broker_records(dlq_topic: str, bootstrap: str, limit: int | None,
                        kafka_bin: str | None, timeout_ms: int) -> Iterator[tuple[str | None, str]]:
    consumer = _kafka_tool("kafka-console-consumer", kafka_bin)
    command = [
        consumer, "--bootstrap-server", bootstrap, "--topic", dlq_topic,
        "--consumer-property", "enable.auto.commit=false",
        "--property", "print.key=true", "--property", "key.separator=\t",
        "--property", "print.value=true",
        "--timeout-ms", str(timeout_ms),
    ]
    if limit is not None:
        command += ["--max-messages", str(limit)]
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.rstrip("\n")
        key, sep, value = line.partition("\t")
        yield (key if sep else None, value if sep else key)
    proc.wait()


def iter_file_records(path: Path) -> Iterator[tuple[str | None, str]]:
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line:
                continue
            key, sep, value = line.partition("\t")
            yield (key if sep else None, value if sep else key)


def produce_actions(actions: Sequence[Action], bootstrap: str, kafka_bin: str | None) -> None:
    to_send = [a for a in actions if not a.skip]
    by_topic: dict[str, list[Action]] = {}
    for action in to_send:
        by_topic.setdefault(action.target_topic, []).append(action)
    producer = _kafka_tool("kafka-console-producer", kafka_bin)
    for topic, group in by_topic.items():
        command = [
            producer, "--bootstrap-server", bootstrap, "--topic", topic,
            "--property", "parse.key=true", "--property", "key.separator=\t",
        ]
        proc = subprocess.Popen(command, stdin=subprocess.PIPE, text=True)
        assert proc.stdin is not None
        for action in group:
            key = action.key if action.key is not None else ""
            # A replayed event must stay on one line; newlines would split the
            # record on the producer side. Payloads are JSON (already single line).
            payload = action.payload.replace("\r", " ").replace("\n", " ")
            proc.stdin.write(f"{key}\t{payload}\n")
        proc.stdin.close()
        rc = proc.wait()
        if rc != 0:
            raise SystemExit(f"[FAIL] producer to {topic} exited {rc}")


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--topic", required=True,
                        help="dead-letter topic (socp-events-dlq) or its main topic (socp-events)")
    parser.add_argument("--bootstrap", default=os.environ.get("SOCP_KAFKA_BOOTSTRAP", "localhost:9092"))
    parser.add_argument("--kafka-bin", default=os.environ.get("KAFKA_BIN_DIR"),
                        help="directory holding kafka-console-consumer/producer (optional)")
    parser.add_argument("--limit", type=int, default=None, help="replay at most N records (small-batch redrive)")
    parser.add_argument("--timeout-ms", type=int, default=10000,
                        help="consumer poll timeout when reading live (default 10000)")
    parser.add_argument("--no-dedup", action="store_true",
                        help="keep duplicate (topic,key) records instead of collapsing per batch")
    parser.add_argument("--force", action="store_true",
                        help="replay even to detect/journal-gated topics. Never bypasses the "
                             "routed-v2 header-loss refusal, which would corrupt routing.")
    parser.add_argument("--dry-run", action="store_true", help="print the plan; do not produce")
    parser.add_argument("--input-file", type=Path, default=None,
                        help="read tab-separated key<TAB>value DLQ records from a file instead of the broker")
    parser.add_argument("--output-file", type=Path, default=None,
                        help="write the planned records as key<TAB>value to a file (offline re-drive)")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    dlq_topic = normalise_dlq_topic(args.topic)
    target = main_topic_for(dlq_topic)

    if args.input_file:
        records = iter_file_records(args.input_file)
    else:
        if args.dry_run and args.timeout_ms == 10000:
            # Reading live in dry-run still needs a broker; warn so an operator
            # reaches for --input-file when none is available.
            print("[note] dry-run will read the live broker; use --input-file for offline triage",
                  file=sys.stderr)
        records = iter_broker_records(dlq_topic, args.bootstrap, args.limit,
                                      args.kafka_bin, args.timeout_ms)

    actions = plan_actions(records, dlq_topic, dedup=not args.no_dedup)

    # Guard: detect-path replay needs a journal reset that the tool cannot do.
    detect_hits = [a for a in actions if not a.skip and target in DETECTION_SKIP_TOPICS]
    routed_hits = [a for a in actions if a.skip and a.skip_reason == "routing-header-not-persisted"]
    sendable = [a for a in actions if not a.skip]
    deduped = [a for a in actions if a.deduped]
    skipped = [a for a in actions if a.skip]

    for action in actions:
        flag = "SKIP" if action.skip else "SEND"
        line = (f"{flag}\ttarget={action.target_topic}\tshape={action.record_shape}"
                f"\tkey={action.key if action.key is not None else '-'}")
        if action.source_offset is not None:
            line += f"\tfrom=partition {action.source_partition}/offset {action.source_offset}"
        if action.reason_code:
            line += f"\treason={action.reason_code}"
        if action.skip_reason:
            line += f"\tskip_reason={action.skip_reason}"
        print(line)
        for note in action.notes:
            print(f"       note: {note}")

    print(f"--- plan: {len(actions)} records; send={len(sendable)} "
          f"skip={len(skipped)} (deduped={len(deduped)}) ---", file=sys.stderr)

    if detect_hits and not args.force:
        print(f"[BLOCKED] {len(detect_hits)} record(s) target detect topic '{target}'. "
              f"detect-web silently skips a repeat of DEAD_LETTERED ids, so re-driving alone "
              f"does not recover detection until the terminal journal row is reset "
              f"(docs/operations/dlq-replay.md). Re-run with --force only after that step.",
              file=sys.stderr)
        return 3
    if routed_hits:
        print(f"[BLOCKED] {len(routed_hits)} record(s) target '{target}' whose routing key is a "
              f"header the DLQ did not persist. Re-driving would misroute; --force does not "
              f"override this. Handle those records via a re-keyed re-ingest, not a raw replay.",
              file=sys.stderr)
        return 3

    if args.output_file:
        with args.output_file.open("w", encoding="utf-8") as handle:
            for action in sendable:
                key = action.key if action.key is not None else ""
                payload = action.payload.replace("\r", " ").replace("\n", " ")
                handle.write(f"{key}\t{payload}\n")
        print(f"wrote {len(sendable)} record(s) to {args.output_file}", file=sys.stderr)
        return 0

    if args.dry_run:
        print("[dry-run] nothing produced to the broker", file=sys.stderr)
        return 0

    if not sendable:
        print("nothing to replay", file=sys.stderr)
        return 0

    produce_actions(sendable, args.bootstrap, args.kafka_bin)
    print(f"replayed {len(sendable)} record(s) to {target}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
