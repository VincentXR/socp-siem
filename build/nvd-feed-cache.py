#!/usr/bin/env python3
"""Stage verified official NVD feeds before Dependency-Check opens its database.

Use feed_url(snapshot) for nvdDatafeedUrl: its {0} must remain literal. Original
.meta files are retained; Dependency-Check generates its own cache properties.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
import gzip
import hashlib
from http.client import HTTPException
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile
import threading
import time
from urllib.error import URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import zlib

BASE_URL = "https://nvd.nist.gov/feeds/json/cve/2.0/"
CHUNK_SIZE = 256 * 1024
RANGE_SIZE = 1024 * 1024
METADATA_LIMIT = 16384
DOWNLOAD_TIMEOUT = 180
# This deadline resets for each bounded Range request, not for a whole feed.
DOWNLOAD_DEADLINE = 180
EARLIEST_ZONE = timezone(timedelta(hours=14))


class FeedError(RuntimeError):
    pass


class FeedCancelled(FeedError):
    pass


class RangeDownloadError(FeedError):
    def __init__(self, message, completed_bytes):
        super().__init__(message)
        self.completed_bytes = completed_bytes


def check_cancelled(cancelled):
    if cancelled is not None and cancelled.is_set():
        raise FeedCancelled("NVD snapshot cancelled after another feed failed")


def default_feed_names(now=None):
    now = now or datetime.now(EARLIEST_ZONE)
    if now.tzinfo is None:
        raise FeedError("Feed year requires a timezone-aware clock")
    return [str(year) for year in range(2002, now.astimezone(EARLIEST_ZONE).year + 1)] + ["modified"]


def _official_url(url):
    parsed = urlsplit(url)
    return (parsed.scheme == "https" and parsed.netloc == "nvd.nist.gov"
            and not parsed.query and not parsed.fragment
            and re.fullmatch(r"/feeds/json/cve/2\.0/nvdcve-2\.0-(?:[0-9]{4}|modified)\.(?:meta|json\.gz)",
                             parsed.path) is not None)


class OfficialRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        if not _official_url(newurl):
            raise FeedError("NVD download redirected outside the official HTTPS feed")
        return super().redirect_request(request, fp, code, message, headers, newurl)


def download(url, destination, maximum_bytes, *, resume=False, progress=None, cancelled=None):
    """Download with verified TLS, bounded reads, and no off-origin redirects."""
    if not _official_url(url):
        raise FeedError("Only official HTTPS NVD feeds are permitted")
    check_cancelled(cancelled)
    if url.endswith(".json.gz"):
        _download_ranges(url, destination, maximum_bytes, resume=resume,
                         progress=progress, cancelled=cancelled)
        return
    request = Request(url, headers={"Accept-Encoding": "identity"})
    started = time.monotonic()
    count = 0
    with build_opener(OfficialRedirects()).open(request, timeout=DOWNLOAD_TIMEOUT) as response:
        length = response.headers.get("Content-Length")
        if length is not None and (not length.isdigit() or int(length) > maximum_bytes):
            raise FeedError("Invalid or oversized NVD HTTP Content-Length")
        with destination.open("wb") as output:
            while True:
                check_cancelled(cancelled)
                if time.monotonic() - started > DOWNLOAD_DEADLINE:
                    raise FeedError("NVD download exceeded its time budget")
                chunk = response.read1(CHUNK_SIZE)
                if not chunk:
                    break
                count += len(chunk)
                if count > maximum_bytes:
                    raise FeedError("NVD download exceeds its declared size")
                output.write(chunk)
    if length is not None and count != int(length):
        raise FeedError("Truncated NVD HTTP response")


def _download_ranges(url, destination, total_bytes, *, resume=False, progress=None, cancelled=None):
    """Retain verified-length chunks when a later HTTP response is truncated.

    The final official uncompressed SHA-256 still validates the entire file;
    ranges alone cannot establish that the publisher served one feed version.
    """
    opener = build_opener(OfficialRedirects())
    offset = destination.stat().st_size if resume and destination.exists() else 0
    if offset > total_bytes or (offset != total_bytes and offset % RANGE_SIZE):
        raise FeedError("NVD resume offset is not a complete validated range boundary")
    with destination.open("r+b" if resume and destination.exists() else "wb") as output:
        for start in range(offset, total_bytes, RANGE_SIZE):
            check_cancelled(cancelled)
            end = min(start + RANGE_SIZE, total_bytes) - 1
            expected = end - start + 1
            for attempt in range(1, 4):
                check_cancelled(cancelled)
                output.seek(start)
                output.truncate(start)
                try:
                    request = Request(url, headers={"Accept-Encoding": "identity",
                                                    "Range": f"bytes={start}-{end}"})
                    started = time.monotonic()
                    with opener.open(request, timeout=DOWNLOAD_TIMEOUT) as response:
                        if response.status != 206 or response.headers.get("Content-Range") != (
                            f"bytes {start}-{end}/{total_bytes}"
                        ):
                            raise FeedError(f"NVD range response status={response.status}, "
                                            f"Content-Range={response.headers.get('Content-Range')!r}; "
                                            f"expected 206 bytes {start}-{end}/{total_bytes}")
                        length = response.headers.get("Content-Length")
                        if length is not None and length != str(expected):
                            raise FeedError("NVD range Content-Length does not match requested bytes")
                        count = 0
                        while True:
                            check_cancelled(cancelled)
                            if time.monotonic() - started > DOWNLOAD_DEADLINE:
                                raise FeedError("NVD range download exceeded its time budget")
                            chunk = response.read1(CHUNK_SIZE)
                            if not chunk:
                                break
                            count += len(chunk)
                            if count > expected:
                                raise FeedError("NVD range response exceeds requested bytes")
                            output.write(chunk)
                        if count != expected:
                            raise FeedError(f"Truncated NVD range response: expected {expected}, received {count}")
                    if progress is not None:
                        progress(f"range {start}-{end} complete; bytes={end + 1}/{total_bytes}; "
                                 f"elapsed={time.monotonic() - started:.1f}s")
                    break
                except FeedCancelled:
                    output.seek(start)
                    output.truncate(start)
                    raise
                except (FeedError, OSError, URLError, HTTPException) as exc:
                    output.seek(start)
                    output.truncate(start)
                    if progress is not None:
                        progress(f"range {start}-{end} attempt {attempt}/3 failed: {exc}; "
                                 f"retained={start}/{total_bytes}")
                    if attempt == 3:
                        raise RangeDownloadError(str(exc), completed_bytes=start) from exc
                    if cancelled is None:
                        time.sleep(attempt)
                    else:
                        cancelled.wait(attempt)


def parse_metadata(content):
    if not content or len(content) > METADATA_LIMIT:
        raise FeedError("Missing or oversized NVD metadata")
    try:
        fields = {}
        for line in content.decode("ascii").splitlines():
            if not line.strip():
                continue
            key, value = line.split(":", 1)
            if key in fields or key != key.strip() or not value:
                raise ValueError("duplicate or invalid metadata field")
            fields[key] = value
        for field in ("size", "gzSize"):
            if not re.fullmatch(r"[1-9][0-9]*", fields[field]):
                raise ValueError(f"invalid {field}")
            fields[field] = int(fields[field])
        if not re.fullmatch(r"[0-9a-fA-F]{64}", fields["sha256"]):
            raise ValueError("invalid sha256")
        fields["timestamp"] = datetime.fromisoformat(fields["lastModifiedDate"].replace("Z", "+00:00"))
        if fields["timestamp"].tzinfo is None:
            raise ValueError("metadata timestamp requires a timezone")
    except (UnicodeError, ValueError, KeyError) as exc:
        raise FeedError(f"Invalid NVD metadata: {exc}") from exc
    return fields


def validate_payload(path, metadata):
    if path.stat().st_size != metadata["gzSize"]:
        raise FeedError("Compressed NVD size does not match metadata")
    digest = hashlib.sha256()
    count = 0
    try:
        with gzip.open(path, "rb") as stream:
            while chunk := stream.read(CHUNK_SIZE):
                count += len(chunk)
                if count > metadata["size"]:
                    raise FeedError("Uncompressed NVD size exceeds metadata")
                digest.update(chunk)
    except (OSError, EOFError, zlib.error) as exc:
        raise FeedError(f"Incomplete or corrupt NVD gzip: {exc}") from exc
    if count != metadata["size"] or digest.hexdigest() != metadata["sha256"].lower():
        raise FeedError("NVD uncompressed size or SHA-256 does not match metadata")


def feed_url(directory):
    directory = Path(directory).resolve()
    if "modified" in str(directory).lower():
        raise FeedError("Feed directory must not contain 'modified' (Dependency-Check URL matching)")
    return directory.as_uri() + "/nvdcve-2.0-{0}.json.gz"


def cache_path(cache_directory, metadata):
    return cache_directory / f"{metadata['sha256'].lower()}-{metadata['gzSize']}.json.gz"


def publish_cached_payload(payload, destination):
    """Cache only a fully validated gzip, independently of snapshot completion."""
    with tempfile.NamedTemporaryFile(prefix=".nvd-", suffix=".part", dir=destination.parent,
                                     delete=False) as temporary:
        pending = Path(temporary.name)
    try:
        shutil.copyfile(payload, pending)
        pending.replace(destination)
    finally:
        pending.unlink(missing_ok=True)


def stage_feeds(directory, feed_names=None, *, fetch=None, attempts=3, retry_delay=2,
                log_directory=None, cache_directory=None):
    """Publish a complete snapshot; injectable fetch(url, path) is for tests.

    Failed downloads retry only their own feed, including refreshed metadata.
    No new snapshot is visible until every requested pair has been validated.
    """
    directory = Path(directory)
    if directory.is_symlink():
        raise FeedError("Feed snapshot cannot replace a symlink")
    directory = directory.resolve()
    feed_url(directory)
    names = list(default_feed_names() if feed_names is None else feed_names)
    if not names or len(set(names)) != len(names) or any(
        not isinstance(name, str) or not (name == "modified" or re.fullmatch(r"[0-9]{4}", name)
                                         and int(name) >= 2002) for name in names
    ):
        raise FeedError("Invalid or duplicate NVD feed names")
    names = [name for name in names if name != "modified"] + (["modified"] if "modified" in names else [])
    if attempts not in (1, 2, 3) or retry_delay < 0:
        raise FeedError("NVD retries must be between one and three with a nonnegative delay")
    if cache_directory is not None:
        cache_directory = Path(cache_directory)
        if cache_directory.is_symlink():
            raise FeedError("Payload cache directory cannot be a symlink")
        cache_directory = cache_directory.resolve()
        if cache_directory.is_relative_to(directory):
            raise FeedError("Payload cache must be outside the temporary feed snapshot")
        cache_directory.mkdir(parents=True, exist_ok=True)
    directory.parent.mkdir(parents=True, exist_ok=True)
    log_path = None
    manifest_path = None
    if log_directory is not None:
        log_path = Path(log_directory) / "feed-download.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path = log_path.with_name("feed-verification.json")
        manifest_path.unlink(missing_ok=True)
        manifest_path.with_suffix(".json.part").unlink(missing_ok=True)
    verified = []
    log_lock = threading.Lock()
    cancelled = threading.Event()

    def log(message):
        with log_lock:
            print(message, flush=True)
            if log_path is not None:
                with log_path.open("a", encoding="utf-8") as output:
                    output.write(message + "\n")

    def acquire(url, path, maximum_bytes, *, resume=False, progress=None):
        check_cancelled(cancelled)
        if fetch is None:
            download(url, path, maximum_bytes, resume=resume, progress=progress, cancelled=cancelled)
        else:
            fetch(url, path)

    with tempfile.TemporaryDirectory(prefix=".nvd-feeds-", dir=directory.parent) as temporary:
        workspace = Path(temporary)
        snapshot = workspace / "snapshot"
        snapshot.mkdir()
        def stage_one(name):
            stem = f"nvdcve-2.0-{name}"
            meta = snapshot / (stem + ".meta.part")
            payload = snapshot / (stem + ".json.gz.part")
            retained_fingerprint = None
            retained_bytes = None
            for attempt in range(1, attempts + 1):
                metadata_fingerprint = None
                try:
                    log(f"NVD feed {name}: attempt {attempt}/{attempts}")
                    acquire(BASE_URL + stem + ".meta", meta, METADATA_LIMIT)
                    meta_bytes = meta.read_bytes()
                    metadata = parse_metadata(meta_bytes)
                    metadata_fingerprint = hashlib.sha256(meta_bytes).hexdigest()
                    if name == "modified":
                        age = datetime.now(timezone.utc) - metadata["timestamp"]
                        if age < -timedelta(minutes=5) or age > timedelta(hours=24):
                            raise FeedError("NVD modified metadata is stale or future dated")
                    resume = (retained_fingerprint == metadata_fingerprint and retained_bytes is not None
                              and payload.is_file() and payload.stat().st_size == retained_bytes)
                    if not resume:
                        if payload.exists():
                            log(f"NVD feed {name}: metadata changed or partial range invalid; discarding partial payload")
                        payload.unlink(missing_ok=True)
                    else:
                        log(f"NVD feed {name}: official metadata unchanged; resuming at {retained_bytes}/{metadata['gzSize']} bytes")
                    cached = cache_path(cache_directory, metadata) if cache_directory is not None else None
                    reused_cache = False
                    if cached is not None and cached.is_symlink():
                        log(f"NVD feed {name}: discarding cached symlink")
                        cached.unlink()
                    if cached is not None and cached.is_file():
                        try:
                            validate_payload(cached, metadata)
                            shutil.copyfile(cached, payload)
                            reused_cache = True
                            log(f"NVD feed {name}: revalidated cached gzip against current official metadata")
                        except (FeedError, OSError) as exc:
                            log(f"NVD feed {name}: cached gzip rejected: {exc}")
                            cached.unlink(missing_ok=True)
                            payload.unlink(missing_ok=True)
                            resume = False
                    if not reused_cache:
                        acquire(BASE_URL + stem + ".json.gz", payload, metadata["gzSize"], resume=resume,
                                progress=lambda message: log(f"NVD feed {name}: {message}"))
                    validate_payload(payload, metadata)
                    if cached is not None and not reused_cache:
                        publish_cached_payload(payload, cached)
                    payload.rename(snapshot / (stem + ".json.gz"))
                    meta.rename(snapshot / (stem + ".meta"))
                    log(f"NVD feed {name}: verified gzip; source={BASE_URL}{stem}.json.gz; "
                        f"lastModifiedDate={metadata['lastModifiedDate']}; "
                        f"gzSize={metadata['gzSize']}; size={metadata['size']}; "
                        f"sha256={metadata['sha256'].lower()}")
                    return {
                        "name": name,
                        "metaURL": BASE_URL + stem + ".meta",
                        "gzURL": BASE_URL + stem + ".json.gz",
                        "gzSize": metadata["gzSize"],
                        "size": metadata["size"],
                        "sha256": metadata["sha256"].lower(),
                        "lastModifiedDate": metadata["lastModifiedDate"],
                        "metaSHA256": metadata_fingerprint,
                    }
                except (FeedError, OSError, URLError, HTTPException) as exc:
                    meta.unlink(missing_ok=True)
                    retain = (isinstance(exc, RangeDownloadError) and metadata_fingerprint is not None
                              and payload.is_file() and payload.stat().st_size == exc.completed_bytes
                              and exc.completed_bytes % RANGE_SIZE == 0)
                    if retain:
                        retained_fingerprint = metadata_fingerprint
                        retained_bytes = exc.completed_bytes
                    else:
                        payload.unlink(missing_ok=True)
                        retained_fingerprint = None
                        retained_bytes = None
                    log(f"NVD feed {name}: attempt failed: {exc}")
                    if attempt == attempts or cancelled.is_set():
                        raise FeedError(f"NVD feed {name} failed after {attempts} attempts") from exc
                    cancelled.wait(retry_delay * attempt)
        annual = [name for name in names if name != "modified"]
        with ThreadPoolExecutor(max_workers=4) as executor:
            # Recent years contain the largest feeds; start them before short historical feeds.
            futures = {executor.submit(stage_one, name): name for name in sorted(annual, reverse=True)}
            results = {}
            try:
                for future in as_completed(futures):
                    results[futures[future]] = future.result()
            except Exception:
                cancelled.set()
                for future in futures:
                    future.cancel()
                raise
        verified.extend(results[name] for name in annual)
        if "modified" in names:
            verified.append(stage_one("modified"))
        previous = workspace / "previous"
        if directory.exists():
            if not directory.is_dir():
                raise FeedError("Feed snapshot destination is not a directory")
            directory.rename(previous)
        try:
            snapshot.rename(directory)
        except OSError:
            if previous.exists():
                previous.rename(directory)
            raise
    if manifest_path is not None:
        pending = manifest_path.with_suffix(".json.part")
        pending.write_text(json.dumps({"snapshot_complete": True, "feeds": verified}, indent=2)
                           + "\n", encoding="utf-8")
        pending.replace(manifest_path)
    return directory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--log-directory", type=Path)
    parser.add_argument("--cache-directory", type=Path)
    args = parser.parse_args()
    try:
        print(feed_url(stage_feeds(args.directory, log_directory=args.log_directory,
                                  cache_directory=args.cache_directory)))
    except (FeedError, OSError) as exc:
        print(f"NVD feed staging failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
