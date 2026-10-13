#!/usr/bin/env python3
"""Bounded real-Vector quiet-tail/retry/replay proof, with optional fault injection.

The preload library is test-only and works with dynamically linked GNU/Linux
binaries. An Alpine/container pass must be established separately by the Java
Testcontainers suite. Memory and known-bad binaries are negative controls only.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import threading
import time


class Receiver:
    def __init__(self):
        self.bodies: dict[str, list[str]] = defaultdict(list)
        self.accepted: set[str] = set()
        self.errors: list[str] = []
        self.unavailable = False
        self.lock = threading.Lock()
        receiver = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                try:
                    if self.path != "/ingest":
                        raise ValueError(f"unexpected path {self.path}")
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 65536:
                        raise ValueError(f"invalid body size {size}")
                    body = self.rfile.read(size).decode("utf-8")
                    event_id = inspect_body(body)
                    with receiver.lock:
                        receiver.bodies[event_id].append(body)
                        attempt = len(receiver.bodies[event_id])
                        status = 503 if receiver.unavailable or attempt <= 8 else 200
                except (ValueError, KeyError, TypeError) as error:
                    with receiver.lock:
                        receiver.errors.append(str(error))
                    status, event_id = 400, None
                self.send_response(status)
                self.send_header("Content-Length", "0")
                self.end_headers()
                if status == 200:
                    with receiver.lock:
                        receiver.accepted.add(event_id)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "attempts": {key: len(value) for key, value in self.bodies.items()},
                "distinct_bodies": {key: len(set(value)) for key, value in self.bodies.items()},
                "accepted": sorted(self.accepted),
                "errors": list(self.errors),
            }

    def reset_for_recovery(self):
        with self.lock:
            self.bodies.clear()
            self.accepted.clear()
            self.errors.clear()
            self.unavailable = False

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def inspect_body(body: str) -> str:
    lines = body.splitlines()
    if len(lines) != 1:
        raise ValueError(f"expected one isolated event, received {len(lines)}")
    envelope = json.loads(lines[0])
    original = json.loads(envelope["message"])
    if not isinstance(envelope.get("eventId"), str) or not envelope["eventId"]:
        raise ValueError("missing stable envelope identity")
    if envelope.get("parse_format") != "auto" or original.get("source") != "auth":
        raise ValueError("changed envelope or original source")
    if original.get("src_ip") != "203.0.113.9" or original.get("host") != "auth-host":
        raise ValueError("changed original event content")
    if original.get("message") != "Failed password " + "x" * 512:
        raise ValueError("changed original message")
    event_id = original["eventId"]
    if not isinstance(event_id, str) or not event_id:
        raise ValueError("missing original event identity")
    return event_id


def input_event(event_id: str) -> str:
    return json.dumps({"eventId": event_id, "source": "auth", "host": "auth-host",
                       "src_ip": "203.0.113.9", "message": "Failed password " + "x" * 512}) + "\n"


def config_text(folder: Path, port: int, buffer: str) -> str:
    # These are the renderer's source/envelope/retry semantics. The exact Java
    # regression also executes the real renderer; this isolated probe adds I/O
    # scheduling injection and old-runtime -> new-runtime replay evidence.
    quote = json.dumps
    return f'''data_dir = {quote(str(folder / "state"))}
[sources.file]
type = "file"
include = [{quote(str(folder / "input.log"))}]
read_from = "beginning"
data_dir = {quote(str(folder / "checkpoint"))}
glob_minimum_cooldown_ms = 1000
[transforms.envelope]
type = "remap"
inputs = ["file"]
source = \'\'\'
.message = string!(.message)
if exists(.host) {{ .collector_host = del(.host) }}
if !exists(.collector_host) {{ .collector_host = "vector-local" }}
.ingested_at = format_timestamp!(now(), format: "%+")
if !exists(.eventId) && !exists(.event.id) && !exists(.event_id) {{ .eventId = uuid_v4() }}
.source_id = "probe-source"
.collector_tag = "test"
.parse_format = "auto"
.parse_rule_ids = []
if exists(.port) {{ del(.port) }}
\'\'\'
[sinks.http]
type = "http"
inputs = ["envelope"]
uri = "http://127.0.0.1:{port}/ingest"
method = "post"
encoding.codec = "json"
framing.method = "newline_delimited"
healthcheck.enabled = false
batch.max_events = 100
batch.timeout_secs = 2
request.retry_initial_backoff_secs = 1
request.retry_max_duration_secs = 1
request.timeout_secs = 30
buffer.type = "{buffer}"
{"buffer.max_size = 268435488" if buffer == "disk" else "buffer.max_events = 500"}
buffer.when_full = "block"
'''


def start(binary: Path, folder: Path, label: str, delay: Path | None):
    environment = dict(os.environ)
    environment.pop("LD_PRELOAD", None)
    if delay:
        environment["LD_PRELOAD"] = str(delay)
    environment["VECTOR_LOG"] = (
        "info,vector_buffers=trace,vector_common::internal_event=trace,"
        "vector::internal_events::file=trace"
    )
    log = (folder / f"{label}.log").open("w")
    process = subprocess.Popen([str(binary), "--config", str(folder / "vector.toml")],
                               stdout=log, stderr=subprocess.STDOUT, env=environment,
                               cwd=folder)
    log.close()
    return process


def stop(process: subprocess.Popen, crash=False):
    if process.poll() is not None:
        return
    if crash:
        process.kill()
    else:
        process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def wait_for(process: subprocess.Popen, predicate, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        if process.poll() is not None:
            raise AssertionError(f"Vector exited with {process.returncode}")
        time.sleep(0.05)
    return predicate()


def validate_delivery(snapshot: dict, expected: list[str]):
    if snapshot["errors"]:
        raise AssertionError(f"receiver rejected payload: {snapshot['errors']}")
    if snapshot["accepted"] != sorted(expected):
        raise AssertionError(f"expected acknowledged IDs {expected}: {snapshot}")
    if snapshot["attempts"] != {event_id: 9 for event_id in expected}:
        raise AssertionError(f"expected eight 503 responses then 200 per event: {snapshot}")
    if snapshot["distinct_bodies"] != {event_id: 1 for event_id in expected}:
        raise AssertionError(f"retry changed envelope bytes: {snapshot}")


def assert_final_quiet(process: subprocess.Popen, receiver: Receiver, expected: list[str]):
    # The last event gets the same independent quiet period as intermediate
    # tails. Never let a tenth request hide in teardown-only bookkeeping.
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise AssertionError(f"Vector exited during the final quiet period: {process.returncode}")
        validate_delivery(receiver.snapshot(), expected)
        time.sleep(0.05)
    validate_delivery(receiver.snapshot(), expected)


def disk_snapshot(folder: Path, label: str) -> dict:
    log = (folder / f"{label}.log").read_text(errors="replace")
    return {
        "data_files": {str(path.relative_to(folder)): path.stat().st_size
                       for path in (folder / "state").rglob("buffer-data-*.dat")},
        "records_written": log.count("Wrote record."),
        "records_read": log.count("Read record."),
        "source_sent": any("source{" in line and "Events sent. count=1" in line
                           for line in log.splitlines()),
    }


def one_trial(args, trial: int) -> dict:
    folder = args.output / f"trial-{trial:02d}"
    folder.mkdir(parents=True, exist_ok=False)
    for name in ("state", "checkpoint"):
        (folder / name).mkdir()
    receiver = Receiver()
    expected = [f"probe-{trial}-{index}" for index in range(args.events)]
    (folder / "input.log").write_text(input_event(expected[0]))
    (folder / "vector.toml").write_text(config_text(folder, receiver.server.server_port, args.buffer))
    writer = args.writer_binary or args.binary
    receiver.unavailable = args.writer_binary is not None
    process = start(writer, folder, "writer" if args.writer_binary else "vector",
                    None if args.writer_binary else args.delay_library)
    label = "writer" if args.writer_binary else "vector"
    result = {"trial": trial, "expected": args.expect, "status": "failed"}
    began = time.monotonic()
    try:
        if args.writer_binary:
            # An HTTP503 proves that the old runtime has read a persisted event;
            # crash before acknowledgement, then remove the source entirely.
            if not wait_for(process, lambda: bool(receiver.snapshot()["attempts"]), args.timeout):
                raise AssertionError("old runtime never attempted its persisted event before crash")
            before = receiver.bodies[expected[0]][0]
            stop(process, crash=True)
            (folder / "input.log").unlink()
            receiver.reset_for_recovery()
            process = start(args.binary, folder, "recovery", args.delay_library)
            label = "recovery"
        else:
            before = None
        for index, event_id in enumerate(expected):
            if index:
                # No other event can serve as a wakeup witness: add the tail only
                # after the preceding event has finished and the topology idles.
                time.sleep(2.2)
                with (folder / "input.log").open("a") as stream:
                    stream.write(input_event(event_id))
            delivered = wait_for(process, lambda: event_id in receiver.snapshot()["accepted"], args.timeout)
            snapshot = receiver.snapshot()
            stage = disk_snapshot(folder, label)
            if args.expect == "stall":
                if delivered or snapshot["attempts"] or snapshot["errors"]:
                    raise AssertionError(f"known-bad control did not stall before HTTP: {snapshot}")
                if not (stage["source_sent"] and stage["records_written"] == 1
                        and stage["records_read"] == 0 and any(stage["data_files"].values())):
                    raise AssertionError(f"stall was not isolated to persisted disk-reader progress: {stage}")
            elif not delivered:
                raise AssertionError(f"quiet event {event_id} did not recover: {snapshot}; {stage}")
            if args.expect == "delivery":
                validate_delivery(snapshot, expected[:index + 1])
            if before is not None and receiver.bodies[event_id][0] != before:
                raise AssertionError("cross-runtime crash replay changed the persisted envelope bytes")
        if args.expect == "delivery":
            assert_final_quiet(process, receiver, expected)
        result.update(status="passed", observation=receiver.snapshot(), stages=disk_snapshot(folder, label))
    except (AssertionError, OSError, ValueError) as error:
        result.update(error=str(error), observation=receiver.snapshot(), stages=disk_snapshot(folder, label))
    finally:
        result["elapsed_seconds"] = round(time.monotonic() - began, 3)
        # The observation is frozen before cleanup. Shutdown can wake a stalled
        # tail and must never retroactively convert the probe into a success.
        (folder / "observation.json").write_text(json.dumps(result, indent=2) + "\n")
        stop(process)
        result["after_cleanup"] = receiver.snapshot()
        (folder / "received.json").write_text(json.dumps(dict(receiver.bodies), indent=2) + "\n")
        receiver.close()
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--writer-binary", type=Path, help="old runtime for disk-only crash replay")
    parser.add_argument("--delay-library", type=Path, help="test-only GNU preload interposer")
    parser.add_argument("--output", type=Path, required=True, help="new directory; never overwrites evidence")
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--events", type=int, default=1, help="separate quiet-tail events in each fresh process")
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--buffer", choices=("disk", "memory"), default="disk")
    parser.add_argument("--expect", choices=("delivery", "stall"), default="delivery")
    args = parser.parse_args(argv)
    if not 1 <= args.rounds <= 30 or not 1 <= args.events <= 10 or not 10 <= args.timeout <= 60:
        parser.error("bounded limits: rounds1..30, events1..10, timeout10..60 seconds")
    if args.expect == "stall" and (not args.delay_library or args.buffer != "disk" or args.events != 1):
        parser.error("stall control requires disk, one event, and a delay library")
    if args.writer_binary and (args.buffer != "disk" or args.events != 1 or args.expect != "delivery"):
        parser.error("cross-runtime replay requires disk, one event, and expected delivery")
    for attribute in ("binary", "writer_binary", "delay_library"):
        value = getattr(args, attribute)
        if value:
            value = value.resolve(strict=True)
            setattr(args, attribute, value)
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    results = []
    for trial in range(args.rounds):
        result = one_trial(args, trial)
        results.append(result)
        print(json.dumps(result), flush=True)
        if result["status"] != "passed":
            break
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    return 0 if len(results) == args.rounds and all(r["status"] == "passed" for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
