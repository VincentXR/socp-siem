"""Bounded, lossless file/process transport for the DLQ operator tool."""
from __future__ import annotations

import base64
import io
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import threading
from dataclasses import dataclass
from typing import Sequence

MAGIC = b"SOCPDLQ1"
FORMAT = "socp-dlq-batch/v1"
MAX_COUNT = 10_000
MAX_RECORD_BYTES = 4 * 1024 * 1024
MAX_BATCH_BYTES = 64 * 1024 * 1024
MAX_HEADERS = 256
MAX_BATCH_HEADERS = 4096
MAX_WIRE_BYTES = MAX_BATCH_BYTES + 4 * 1024 * 1024
MAX_FILE_BYTES = 96 * 1024 * 1024
MAX_STDERR_BYTES = 64 * 1024


class TransportError(RuntimeError):
    pass


@dataclass(frozen=True)
class Record:
    topic: str
    key: bytes | None
    value: bytes | None
    partition: int = 0
    offset: int = 0
    timestamp: int = -1
    headers: tuple[tuple[str, bytes | None], ...] = ()


def topic_name(value: str) -> str:
    if not isinstance(value, str) or value in {".", ".."} or not re.fullmatch(r"[A-Za-z0-9._-]{1,249}", value):
        raise ValueError("invalid Kafka topic name")
    return value


def validate_records(records: Sequence[Record], topic: str | None = None) -> None:
    if len(records) > MAX_COUNT:
        raise ValueError("batch exceeds 10000 records")
    total = header_count = 0
    for record in records:
        topic_name(record.topic)
        if topic is not None and record.topic != topic:
            raise ValueError("batch source topic differs from --topic")
        for coordinate in (record.partition, record.offset, record.timestamp):
            if type(coordinate) is not int or coordinate < -1 or coordinate > 2**63 - 1:
                raise ValueError("invalid record coordinates")
        if record.partition > 2**31 - 1:
            raise ValueError("partition out of range")
        if len(record.headers) > MAX_HEADERS:
            raise ValueError("record exceeds 256 headers")
        size = len(record.topic.encode("utf-8"))
        for value in (record.key, record.value):
            if value is not None:
                if not isinstance(value, bytes):
                    raise ValueError("record key/value must be bytes or null")
                size += len(value)
        for name, value in record.headers:
            if not isinstance(name, str) or len(name.encode("utf-8")) > 256:
                raise ValueError("invalid header name")
            if value is not None and not isinstance(value, bytes):
                raise ValueError("header value must be bytes or null")
            size += len(name.encode("utf-8")) + len(value or b"")
        total += size
        header_count += len(record.headers)
        if size > MAX_RECORD_BYTES or total > MAX_BATCH_BYTES or header_count > MAX_BATCH_HEADERS:
            raise ValueError("record/batch byte or total-header limit exceeded; use a smaller batch")


def strict_json(text: str):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON field")
            result[key] = value
        return result
    def constant(_):
        raise ValueError("non-finite JSON value")
    return json.loads(text, object_pairs_hook=pairs, parse_constant=constant)


def _base64(value: bytes | None) -> str | None:
    return None if value is None else base64.b64encode(value).decode("ascii")


def _unbase64(value) -> bytes | None:
    if value is None:
        return None
    if not isinstance(value, str) or len(value) > (MAX_RECORD_BYTES + 2) // 3 * 4:
        raise ValueError("invalid/oversized Base64 field")
    decoded = base64.b64decode(value, validate=True)
    if _base64(decoded) != value:
        raise ValueError("non-canonical Base64 field")
    return decoded


def write_batch(path: Path, records: Sequence[Record], topic: str) -> None:
    validate_records(records, topic)
    data = {"format": FORMAT, "sourceTopic": topic, "records": [
        {"topic": r.topic, "partition": r.partition, "offset": r.offset, "timestamp": r.timestamp,
         "keyBase64": _base64(r.key), "valueBase64": _base64(r.value),
         "headers": [{"key": key, "valueBase64": _base64(value)} for key, value in r.headers]}
        for r in records]}
    encoded = (json.dumps(data, ensure_ascii=True, separators=(",", ":")) + "\n").encode("ascii")
    if len(encoded) > MAX_FILE_BYTES:
        raise ValueError("encoded export exceeds 96 MiB; use a smaller batch")
    # Never overwrite an evidence file (including the input). Truncated writes are
    # invalid JSON and rejected on input, rather than becoming a shorter success.
    with path.open("xb") as handle:
        handle.write(encoded)


def read_batch(path: Path, topic: str) -> list[Record]:
    with path.open("rb") as handle:
        encoded = handle.read(MAX_FILE_BYTES + 1)
    if len(encoded) > MAX_FILE_BYTES:
        raise ValueError("input exceeds 96 MiB")
    data = strict_json(encoded.decode("utf-8"))
    if not isinstance(data, dict) or set(data) != {"format", "sourceTopic", "records"} or data["format"] != FORMAT:
        raise ValueError("expected socp-dlq-batch/v1; console TSV cannot preserve bytes/nulls and is not replayable")
    if data["sourceTopic"] != topic or not isinstance(data["records"], list) or len(data["records"]) > MAX_COUNT:
        raise ValueError("invalid source topic or record count")
    rows = []
    total_bytes = total_headers = 0
    for item in data["records"]:
        if not isinstance(item, dict) or set(item) != {"topic", "partition", "offset", "timestamp", "keyBase64", "valueBase64", "headers"}:
            raise ValueError("invalid record fields")
        headers = item["headers"]
        if not isinstance(headers, list) or len(headers) > MAX_HEADERS:
            raise ValueError("invalid headers")
        decoded_headers = []
        for header in headers:
            if not isinstance(header, dict) or set(header) != {"key", "valueBase64"}:
                raise ValueError("invalid header fields")
            decoded_headers.append((header["key"], _unbase64(header["valueBase64"])))
        rows.append(Record(item["topic"], _unbase64(item["keyBase64"]), _unbase64(item["valueBase64"]),
                           item["partition"], item["offset"], item["timestamp"], tuple(decoded_headers)))
        # Fail before decoding more records once a cumulative limit is reached.
        row = rows[-1]
        validate_records([row], topic)
        total_bytes += (len(row.topic.encode("utf-8")) + len(row.key or b"") + len(row.value or b"")
                        + sum(len(name.encode("utf-8")) + len(value or b"") for name, value in row.headers))
        total_headers += len(row.headers)
        if total_bytes > MAX_BATCH_BYTES or total_headers > MAX_BATCH_HEADERS:
            raise ValueError("batch bytes/headers exceed limit")
    validate_records(rows, topic)
    return rows


def encode_wire(records: Sequence[Record]) -> bytes:
    validate_records(records)
    out = io.BytesIO()
    out.write(MAGIC + struct.pack(">i", len(records)))
    def blob(value):
        out.write(struct.pack(">i", -1 if value is None else len(value)))
        if value is not None:
            out.write(value)
    for record in records:
        blob(record.topic.encode("utf-8"))
        out.write(struct.pack(">iqq", record.partition, record.offset, record.timestamp))
        blob(record.key)
        blob(record.value)
        out.write(struct.pack(">i", len(record.headers)))
        for key, value in record.headers:
            blob(key.encode("utf-8"))
            blob(value)
    return out.getvalue()


def decode_wire(data: bytes) -> list[Record]:
    if len(data) > MAX_WIRE_BYTES:
        raise ValueError("transport output exceeds limit")
    source = io.BytesIO(data)
    def take(length):
        value = source.read(length)
        if len(value) != length:
            raise ValueError("truncated transport output")
        return value
    def integer():
        return struct.unpack(">i", take(4))[0]
    def blob(maximum=MAX_RECORD_BYTES):
        length = integer()
        if length == -1:
            return None
        if length < 0 or length > maximum:
            raise ValueError("invalid transport field length")
        return take(length)
    if take(len(MAGIC)) != MAGIC:
        raise ValueError("invalid transport magic")
    count = integer()
    if count < 0 or count > MAX_COUNT:
        raise ValueError("invalid transport count")
    rows = []
    for _ in range(count):
        topic = blob(249)
        if topic is None:
            raise ValueError("missing transport topic")
        partition, offset, timestamp = struct.unpack(">iqq", take(20))
        key, value = blob(), blob()
        header_count = integer()
        if header_count < 0 or header_count > MAX_HEADERS:
            raise ValueError("invalid transport header count")
        headers = []
        for _ in range(header_count):
            name = blob(256)
            if name is None:
                raise ValueError("null header name")
            headers.append((name.decode("utf-8"), blob()))
        rows.append(Record(topic.decode("utf-8"), key, value, partition, offset, timestamp, tuple(headers)))
    if source.read(1):
        raise ValueError("trailing transport bytes")
    validate_records(rows)
    return rows


def run_process(command: Sequence[str], payload: bytes | None, timeout: float,
                output_limit: int = MAX_WIRE_BYTES) -> bytes:
    """Drain both pipes; kill/reap on timeout/overflow; never accept a partial failed read."""
    proc = subprocess.Popen(list(command), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout = bytearray()
    stderr = bytearray()
    overflow = threading.Event()
    write_failed = threading.Event()
    def kill():
        try:
            proc.kill()
        except OSError:
            pass  # The process may have exited between wait/overflow and kill.
    def drain(pipe, output, maximum, fatal):
        while True:
            chunk = pipe.read(65536)
            if not chunk:
                return
            room = max(0, maximum - len(output))
            output.extend(chunk[:room])
            if fatal and len(chunk) > room:
                overflow.set()
                kill()
    def write():
        try:
            if payload:
                proc.stdin.write(payload)
                proc.stdin.flush()
        except (BrokenPipeError, OSError):
            write_failed.set()
        finally:
            try:
                proc.stdin.close()
            except OSError:
                pass
    workers = [threading.Thread(target=drain, args=(proc.stdout, stdout, output_limit, True), daemon=True),
               threading.Thread(target=drain, args=(proc.stderr, stderr, MAX_STDERR_BYTES, False), daemon=True),
               threading.Thread(target=write, daemon=True)]
    for worker in workers:
        worker.start()
    timed_out = False
    try:
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        kill()
    except BaseException:
        kill()
        raise
    finally:
        proc.wait(timeout=5)
        for worker in workers:
            worker.join(timeout=5)
        if not any(worker.is_alive() for worker in workers):
            proc.stdout.close()
            proc.stderr.close()
    if any(worker.is_alive() for worker in workers):
        raise TransportError("transport did not release its pipes")
    if timed_out or overflow.is_set() or proc.returncode != 0 or write_failed.is_set():
        reason = "deadline exceeded" if timed_out else "output limit exceeded" if overflow.is_set() else f"exit {proc.returncode}"
        # Kafka diagnostics can contain endpoint/authentication configuration. Do
        # not echo arbitrary stderr, payload fragments, or stack traces.
        raise TransportError(f"Kafka transport failed ({reason}); no successful batch acknowledgement")
    return bytes(stdout)


def bridge_command(java: str | None, classpath: str | None, kafka_bin: str | None) -> list[str]:
    executable = java or shutil.which("java")
    if not executable:
        raise TransportError("Java 21 is required; set --java")
    if not classpath:
        location = Path(kafka_bin) if kafka_bin else None
        if location is None:
            found = shutil.which("kafka-run-class.sh") or shutil.which("kafka-run-class.bat")
            if found:
                location = Path(found).parent
            elif os.environ.get("KAFKA_HOME"):
                location = Path(os.environ["KAFKA_HOME"]) / "bin"
        if location:
            for parent in (location.parent, location.parent.parent):
                libs = parent / "libs"
                if libs.is_dir() and any(libs.glob("kafka-clients-*.jar")):
                    classpath = str(libs / "*")
                    break
    if not classpath:
        raise TransportError("Kafka client jars required: use --kafka-classpath or --kafka-bin from a Kafka installation")
    return [executable, "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "--class-path", classpath,
            str(Path(__file__).resolve().parent / "kafka" / "DlqTransport.java")]
