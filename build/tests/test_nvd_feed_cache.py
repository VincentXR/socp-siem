import contextlib
from datetime import datetime, timedelta, timezone
import gzip
import hashlib
from http.client import IncompleteRead
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("nvd_feeds", ROOT / "build/nvd-feed-cache.py")
FEEDS = importlib.util.module_from_spec(spec)
spec.loader.exec_module(FEEDS)


class NvdFeedCacheTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = self.root / "feeds"
        self.raw = b'{"vulnerabilities":[{"cve":{"id":"CVE-2012-1234"}}]}'
        self.compressed = gzip.compress(self.raw)
        self.meta = self.metadata()
        self.calls = []

    def metadata(self, raw=None, compressed=None, timestamp=None):
        raw = self.raw if raw is None else raw
        compressed = self.compressed if compressed is None else compressed
        timestamp = timestamp or datetime.now(timezone.utc).isoformat()
        return (f"lastModifiedDate:{timestamp}\r\nsize:{len(raw)}\r\n"
                f"gzSize:{len(compressed)}\r\n"
                f"sha256:{hashlib.sha256(raw).hexdigest().upper()}\r\n").encode("ascii")

    def fetch(self, url, destination):
        self.calls.append(url)
        self.assertTrue(url.startswith(FEEDS.BASE_URL))
        destination.write_bytes(self.meta if url.endswith(".meta") else self.compressed)

    def stage(self, names=None, fetch=None, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return FEEDS.stage_feeds(self.directory, names or ["2012", "modified"],
                                     fetch=fetch or self.fetch, retry_delay=0, **kwargs)

    def test_snapshot_preserves_metadata_and_downloads_modified_last(self):
        result = self.stage(["modified", "2012", "2013"])
        self.assertEqual(self.directory, result)
        self.assertEqual(self.meta, (result / "nvdcve-2.0-2012.meta").read_bytes())
        self.assertEqual(self.compressed, (result / "nvdcve-2.0-2012.json.gz").read_bytes())
        meta_names = [url.split("nvdcve-2.0-")[1].split(".")[0]
                      for url in self.calls if url.endswith(".meta")]
        self.assertCountEqual(["2012", "2013"], meta_names[:-1])
        self.assertEqual("modified", meta_names[-1])
        self.assertFalse(list(result.glob("*.part")))
        self.assertFalse((result / "cache.properties").exists())

    def test_year_boundary_includes_full_history_using_utc_plus_fourteen(self):
        now = datetime(2025, 12, 31, 10, 0, tzinfo=timezone.utc)
        self.assertEqual([str(year) for year in range(2002, 2027)] + ["modified"],
                         FEEDS.default_feed_names(now))

    def test_at_most_four_annual_feeds_run_together_and_modified_waits_for_all(self):
        barrier = threading.Barrier(4)
        lock = threading.Lock()
        active = 0
        peak = 0
        finished = set()
        validated = set()
        annual = [str(year) for year in range(2012, 2020)]
        original_validate = FEEDS.validate_payload

        def validate(path, metadata):
            original_validate(path, metadata)
            with lock:
                validated.add(path.name.split("nvdcve-2.0-")[1].split(".")[0])

        def fetch(url, destination):
            nonlocal active, peak
            name = url.split("nvdcve-2.0-")[1].split(".")[0]
            if name == "modified":
                self.assertEqual(set(annual), finished)
                self.assertEqual(set(annual), validated)
            elif url.endswith(".meta"):
                with lock:
                    active += 1
                    peak = max(peak, active)
                barrier.wait(timeout=5)
            self.fetch(url, destination)
            if name != "modified" and url.endswith(".json.gz"):
                with lock:
                    active -= 1
                    finished.add(name)

        logs = self.root / "parallel-logs"
        with patch.object(FEEDS, "validate_payload", side_effect=validate):
            self.stage(["modified", *annual], fetch=fetch, log_directory=logs)
        self.assertEqual(4, peak)
        manifest = json.loads((logs / "feed-verification.json").read_text())
        self.assertEqual([*annual, "modified"], [feed["name"] for feed in manifest["feeds"]])

    def test_metadata_rejects_missing_duplicate_invalid_hash_and_naive_date(self):
        bad = [b"", self.meta.replace(b"size:", b"missing:"),
               self.meta + b"size:1\n", self.meta.replace(b"gzSize:", b"gzSize:-"),
               self.meta.replace(b"sha256:", b"sha256:xyz"),
               self.metadata(timestamp="2026-01-01T00:00:00")]
        for content in bad:
            with self.subTest(content=content), self.assertRaises(FEEDS.FeedError):
                FEEDS.parse_metadata(content)

    def test_truncated_gzip_and_bad_crc_fail_even_with_matching_compressed_size(self):
        bad_crc = self.compressed[:-8] + bytes([self.compressed[-8] ^ 1]) + self.compressed[-7:]
        for payload in (self.compressed[:-5], bad_crc):
            with self.subTest(payload=payload):
                path = self.root / "bad.gz"
                path.write_bytes(payload)
                meta = FEEDS.parse_metadata(self.metadata(compressed=payload))
                with self.assertRaises(FEEDS.FeedError):
                    FEEDS.validate_payload(path, meta)

    def test_compressed_length_raw_length_and_hash_mismatches_fail(self):
        path = self.root / "payload.gz"
        path.write_bytes(self.compressed)
        for field, value in (("gzSize", len(self.compressed) + 1),
                             ("size", len(self.raw) - 1), ("size", len(self.raw) + 1),
                             ("sha256", "0" * 64)):
            with self.subTest(field=field, value=value):
                meta = FEEDS.parse_metadata(self.meta)
                meta[field] = value
                with self.assertRaises(FEEDS.FeedError):
                    FEEDS.validate_payload(path, meta)

    def test_one_bad_feed_retries_with_refreshed_metadata_without_redownloading_good_year(self):
        failures = [True]

        def fetch(url, destination):
            self.fetch(url, destination)
            if url.endswith("2013.json.gz") and failures:
                failures.pop()
                destination.write_bytes(self.compressed[:-4])

        self.stage(["2012", "2013", "modified"], fetch=fetch)
        self.assertEqual(1, self.calls.count(FEEDS.BASE_URL + "nvdcve-2.0-2012.json.gz"))
        self.assertEqual(2, self.calls.count(FEEDS.BASE_URL + "nvdcve-2.0-2013.meta"))
        self.assertEqual(2, self.calls.count(FEEDS.BASE_URL + "nvdcve-2.0-2013.json.gz"))

    def test_mid_transfer_exception_retries_then_publishes(self):
        attempts = []

        def fetch(url, destination):
            self.fetch(url, destination)
            if url.endswith(".json.gz"):
                attempts.append(url)
                if len(attempts) == 1:
                    raise IncompleteRead(b"partial", 100)

        self.stage(["2012"], fetch=fetch)
        self.assertEqual(2, len(attempts))

    def test_metadata_publication_race_reloads_the_pair(self):
        changed_raw = self.raw + b"\n"
        changed_payload = gzip.compress(changed_raw)
        meta_requests = []

        def fetch(url, destination):
            if url.endswith(".meta"):
                meta_requests.append(url)
                destination.write_bytes(self.meta if len(meta_requests) == 1
                                        else self.metadata(changed_raw, changed_payload))
            else:
                destination.write_bytes(changed_payload)

        self.stage(["2012"], fetch=fetch)
        self.assertEqual(2, len(meta_requests))
        self.assertEqual(changed_payload, (self.directory / "nvdcve-2.0-2012.json.gz").read_bytes())

    def test_retry_exhaustion_preserves_old_snapshot_and_diagnostics(self):
        self.directory.mkdir()
        (self.directory / "old").write_text("known good")

        def fetch(url, destination):
            self.fetch(url, destination)
            if "2013.json.gz" in url:
                destination.write_bytes(b"partial")

        with self.assertRaises(FEEDS.FeedError):
            self.stage(["2012", "2013"], fetch=fetch, log_directory=self.root / "logs")
        self.assertEqual(["old"], [entry.name for entry in self.directory.iterdir()])
        self.assertEqual("known good", (self.directory / "old").read_text())
        self.assertEqual(3, self.calls.count(FEEDS.BASE_URL + "nvdcve-2.0-2013.meta"))
        self.assertIn("attempt failed", (self.root / "logs/feed-download.log").read_text())
        self.assertFalse(list(self.root.glob(".nvd-feeds-*")))

    def test_failed_first_snapshot_publishes_nothing(self):
        def fetch(url, destination):
            self.fetch(url, destination)
            if "2013.meta" in url:
                raise OSError("missing annual feed")

        with self.assertRaises(FEEDS.FeedError):
            self.stage(["2012", "2013"], fetch=fetch)
        self.assertFalse(self.directory.exists())

    def test_manifest_records_all_verified_sources_and_failed_run_removes_old_success(self):
        logs = self.root / "logs"
        self.stage(log_directory=logs)
        manifest_path = logs / "feed-verification.json"
        manifest = json.loads(manifest_path.read_text())
        self.assertTrue(manifest["snapshot_complete"])
        self.assertEqual(["2012", "modified"], [feed["name"] for feed in manifest["feeds"]])
        annual = manifest["feeds"][0]
        self.assertEqual(hashlib.sha256(self.meta).hexdigest(), annual["metaSHA256"])
        self.assertEqual(hashlib.sha256(self.raw).hexdigest(), annual["sha256"])
        self.assertEqual(FEEDS.BASE_URL + "nvdcve-2.0-2012.meta", annual["metaURL"])
        self.assertEqual(FEEDS.BASE_URL + "nvdcve-2.0-2012.json.gz", annual["gzURL"])

        def fetch(url, destination):
            self.fetch(url, destination)
            if "modified.json.gz" in url:
                destination.write_bytes(b"corrupt")

        with self.assertRaises(FEEDS.FeedError):
            self.stage(fetch=fetch, attempts=1, log_directory=logs)
        self.assertFalse(manifest_path.exists())
        self.assertFalse(list(logs.glob("*.part")))

    def test_stale_or_future_modified_metadata_is_rejected(self):
        for offset in (timedelta(days=-2), timedelta(hours=1)):
            self.meta = self.metadata(timestamp=(datetime.now(timezone.utc) + offset).isoformat())
            with self.subTest(offset=offset), self.assertRaises(FEEDS.FeedError):
                self.stage(["modified"], attempts=1)
            self.assertFalse(self.directory.exists())

    def test_file_url_retains_literal_placeholder_and_rejects_modified_parent(self):
        url = FEEDS.feed_url(self.root / "feed space")
        self.assertTrue(url.endswith("feed%20space/nvdcve-2.0-{0}.json.gz"))
        with self.assertRaises(FEEDS.FeedError):
            FEEDS.feed_url(self.root / "Modified-cache")

    def test_network_redirect_cannot_leave_official_https_origin(self):
        redirect = FEEDS.OfficialRedirects()
        for url in ("http://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-2012.meta",
                    "https://example.com/nvdcve-2.0-2012.meta"):
            with self.subTest(url=url), self.assertRaises(FEEDS.FeedError):
                redirect.redirect_request(None, None, 302, "redirect", {}, url)

    def test_downloader_detects_truncated_http_before_publish(self):
        class Response(io.BytesIO):
            headers = {"Content-Length": "10"}

        response = Response(b"short")
        with patch.object(FEEDS, "build_opener") as opener:
            opener.return_value.open.return_value = response
            with self.assertRaisesRegex(FEEDS.FeedError, "Truncated"):
                FEEDS.download(FEEDS.BASE_URL + "nvdcve-2.0-2012.meta", self.root / "partial", 100)

    @staticmethod
    def range_response(data, start, end, total, status=206, content_range=None):
        response = io.BytesIO(data)
        response.status = status
        response.headers = {"Content-Length": str(end - start + 1),
                            "Content-Range": content_range or f"bytes {start}-{end}/{total}"}
        return response

    def test_range_retry_retains_previous_chunk_and_discards_partial_current_chunk(self):
        requests = []

        def respond(request, timeout):
            requested = request.get_header("Range")
            requests.append(requested)
            if requested == "bytes=0-3":
                return self.range_response(b"abcd", 0, 3, 10)
            if requested == "bytes=4-7":
                return self.range_response(b"ef" if requests.count(requested) == 1 else b"efgh", 4, 7, 10)
            return self.range_response(b"ij", 8, 9, 10)

        path = self.root / "ranges.gz"
        with patch.object(FEEDS, "RANGE_SIZE", 4), patch.object(FEEDS, "build_opener") as opener, \
                patch.object(FEEDS.time, "sleep"):
            opener.return_value.open.side_effect = respond
            FEEDS.download(FEEDS.BASE_URL + "nvdcve-2.0-2012.json.gz", path, 10)
        self.assertEqual(b"abcdefghij", path.read_bytes())
        self.assertEqual(["bytes=0-3", "bytes=4-7", "bytes=4-7", "bytes=8-9"], requests)

    def test_range_rejects_ignored_ranges_wrong_offsets_and_changed_total(self):
        for status, content_range in ((200, "bytes 0-3/4"), (206, "bytes 1-4/4"),
                                      (206, "bytes 0-3/5")):
            with self.subTest(status=status, content_range=content_range):
                path = self.root / "rejected.gz"
                with patch.object(FEEDS, "build_opener") as opener, patch.object(FEEDS.time, "sleep"):
                    opener.return_value.open.side_effect = lambda *args, **kwargs: self.range_response(
                        b"abcd", 0, 3, 4, status=status, content_range=content_range)
                    with self.assertRaises(FEEDS.FeedError):
                        FEEDS.download(FEEDS.BASE_URL + "nvdcve-2.0-2012.json.gz", path, 4)
                    self.assertEqual(3, opener.return_value.open.call_count)
                self.assertEqual(b"", path.read_bytes())

    def test_range_retry_exhaustion_keeps_only_prior_complete_chunks(self):
        def respond(request, timeout):
            if request.get_header("Range") == "bytes=0-3":
                return self.range_response(b"abcd", 0, 3, 8)
            return self.range_response(b"e", 4, 7, 8)

        path = self.root / "failed.gz"
        with patch.object(FEEDS, "RANGE_SIZE", 4), patch.object(FEEDS, "build_opener") as opener, \
                patch.object(FEEDS.time, "sleep"):
            opener.return_value.open.side_effect = respond
            with self.assertRaisesRegex(FEEDS.FeedError, "Truncated"):
                FEEDS.download(FEEDS.BASE_URL + "nvdcve-2.0-2012.json.gz", path, 8)
            self.assertEqual(4, opener.return_value.open.call_count)
        self.assertEqual(b"abcd", path.read_bytes())

    def test_resumed_download_requests_only_ranges_after_verified_boundary(self):
        path = self.root / "resume.gz"
        path.write_bytes(b"abcd")
        requests = []

        def respond(request, timeout):
            requests.append(request.get_header("Range"))
            return self.range_response(b"efgh", 4, 7, 8)

        with patch.object(FEEDS, "RANGE_SIZE", 4), patch.object(FEEDS, "build_opener") as opener:
            opener.return_value.open.side_effect = respond
            FEEDS.download(FEEDS.BASE_URL + "nvdcve-2.0-2012.json.gz", path, 8, resume=True)
        self.assertEqual(["bytes=4-7"], requests)
        self.assertEqual(b"abcdefgh", path.read_bytes())
        path.write_bytes(b"abc")
        with patch.object(FEEDS, "RANGE_SIZE", 4), patch.object(FEEDS, "build_opener") as opener:
            with self.assertRaisesRegex(FEEDS.FeedError, "boundary"):
                FEEDS.download(FEEDS.BASE_URL + "nvdcve-2.0-2012.json.gz", path, 8, resume=True)
            opener.return_value.open.assert_not_called()

    def test_same_metadata_retains_completed_ranges_across_feed_attempts(self):
        payload_requests = []
        meta_requests = []

        def fetch(url, destination):
            if url.endswith(".meta"):
                meta_requests.append(url)
                destination.write_bytes(self.meta)
            else:
                payload_requests.append(url)
                if len(payload_requests) == 1:
                    destination.write_bytes(self.compressed[:4])
                    raise FEEDS.RangeDownloadError("range exhausted", completed_bytes=4)
                self.assertEqual(self.compressed[:4], destination.read_bytes())
                with destination.open("ab") as output:
                    output.write(self.compressed[4:])

        with patch.object(FEEDS, "RANGE_SIZE", 4):
            self.stage(["2012"], fetch=fetch)
        self.assertEqual(2, len(meta_requests))
        self.assertEqual(self.compressed, (self.directory / "nvdcve-2.0-2012.json.gz").read_bytes())

    def test_changed_metadata_fingerprint_discards_previous_ranges(self):
        for change in ("sha256", "gzSize", "timestamp"):
            with self.subTest(change=change):
                attempts = []
                changed_raw = self.raw + b" " if change == "sha256" else self.raw
                changed_gzip = gzip.compress(changed_raw)
                if change == "gzSize":
                    changed_gzip += gzip.compress(b"")
                changed_meta = self.metadata(changed_raw, changed_gzip,
                                             timestamp=(datetime.now(timezone.utc) + timedelta(seconds=1)).isoformat())

                def fetch(url, destination):
                    if url.endswith(".meta"):
                        attempts.append(url)
                        destination.write_bytes(self.meta if len(attempts) == 1 else changed_meta)
                    elif len(attempts) == 1:
                        destination.write_bytes(self.compressed[:4])
                        raise FEEDS.RangeDownloadError("range exhausted", completed_bytes=4)
                    else:
                        self.assertFalse(destination.exists())
                        destination.write_bytes(changed_gzip)

                with patch.object(FEEDS, "RANGE_SIZE", 4):
                    self.stage(["2012"], fetch=fetch)
                self.assertEqual(2, len(attempts))

    def test_final_gzip_or_sha_failure_discards_payload_before_retry(self):
        bad_crc = self.compressed[:-8] + bytes([self.compressed[-8] ^ 1]) + self.compressed[-7:]
        wrong_sha = gzip.compress(self.raw.replace(b"1234", b"1235"))
        self.assertEqual(len(self.compressed), len(wrong_sha))
        for corrupt in (bad_crc, wrong_sha):
            with self.subTest(corrupt=corrupt):
                attempts = []

                def fetch(url, destination):
                    if url.endswith(".meta"):
                        destination.write_bytes(self.meta)
                    else:
                        attempts.append(url)
                        self.assertFalse(destination.exists())
                        destination.write_bytes(corrupt if len(attempts) == 1 else self.compressed)

                self.stage(["2012"], fetch=fetch)
                self.assertEqual(2, len(attempts))

    def test_partial_range_marker_is_not_accepted_as_resume_boundary(self):
        requests = []

        def fetch(url, destination):
            if url.endswith(".meta"):
                destination.write_bytes(self.meta)
            else:
                requests.append(url)
                if len(requests) == 1:
                    destination.write_bytes(self.compressed[:3])
                    raise FEEDS.RangeDownloadError("partial", completed_bytes=3)
                self.assertFalse(destination.exists())
                destination.write_bytes(self.compressed)

        with patch.object(FEEDS, "RANGE_SIZE", 4):
            self.stage(["2012"], fetch=fetch)

    def test_range_progress_is_reported_and_cancellation_stops_next_request(self):
        event = threading.Event()
        messages = []

        def progress(message):
            messages.append(message)
            event.set()

        path = self.root / "cancel.gz"
        with patch.object(FEEDS, "RANGE_SIZE", 4), patch.object(FEEDS, "build_opener") as opener:
            opener.return_value.open.return_value = self.range_response(b"abcd", 0, 3, 8)
            with self.assertRaises(FEEDS.FeedCancelled):
                FEEDS.download(FEEDS.BASE_URL + "nvdcve-2.0-2012.json.gz", path, 8,
                               progress=progress, cancelled=event)
            self.assertEqual(1, opener.return_value.open.call_count)
        self.assertIn("bytes=4/8", messages[0])
        self.assertEqual(b"abcd", path.read_bytes())

    def test_newest_years_start_first_but_manifest_remains_chronological(self):
        barrier = threading.Barrier(4)
        first_started = []
        lock = threading.Lock()

        def fetch(url, destination):
            if url.endswith(".meta"):
                with lock:
                    first_started.append(url.split("nvdcve-2.0-")[1].split(".")[0])
                    first_group = len(first_started) <= 4
                if first_group:
                    barrier.wait(timeout=5)
            self.fetch(url, destination)

        logs = self.root / "priority-logs"
        names = [str(year) for year in range(2002, 2027)]
        self.stage(names, fetch=fetch, log_directory=logs)
        self.assertCountEqual(["2026", "2025", "2024", "2023"], first_started[:4])
        self.assertEqual(names, [entry["name"] for entry in
                                json.loads((logs / "feed-verification.json").read_text())["feeds"]])

    def test_cached_payload_requires_current_metadata_and_full_revalidation(self):
        cache = self.root / "payload-cache"
        self.stage(["2012"], cache_directory=cache)
        self.calls.clear()
        self.stage(["2012"], cache_directory=cache)
        self.assertEqual([FEEDS.BASE_URL + "nvdcve-2.0-2012.meta"], self.calls)
        cached = next(cache.glob("*.json.gz"))
        wrong_sha = gzip.compress(self.raw.replace(b"1234", b"1235"))
        self.assertEqual(len(self.compressed), len(wrong_sha))
        cached.write_bytes(wrong_sha)
        self.calls.clear()
        self.stage(["2012"], cache_directory=cache)
        self.assertEqual(2, len(self.calls))
        self.assertEqual(self.compressed, cached.read_bytes())
        self.assertFalse(list(cache.glob("*.part")))

    def test_verified_cache_survives_later_failure_without_publishing_snapshot(self):
        cache = self.root / "payload-cache"

        def fetch(url, destination):
            self.fetch(url, destination)
            if "modified.meta" in url:
                raise OSError("modified feed unavailable")

        with self.assertRaises(FEEDS.FeedError):
            self.stage(fetch=fetch, attempts=1, cache_directory=cache)
        self.assertFalse(self.directory.exists())
        self.assertEqual([self.compressed], [path.read_bytes() for path in cache.glob("*.json.gz")])

    def test_cache_cannot_override_new_source_hash_or_stale_modified_metadata(self):
        cache = self.root / "payload-cache"
        self.stage(["modified"], cache_directory=cache)
        self.meta = self.metadata(timestamp=(datetime.now(timezone.utc) - timedelta(days=2)).isoformat())
        self.calls.clear()
        with self.assertRaises(FEEDS.FeedError):
            self.stage(["modified"], attempts=1, cache_directory=cache)
        self.assertEqual([FEEDS.BASE_URL + "nvdcve-2.0-modified.meta"], self.calls)
        self.raw += b" "
        self.compressed = gzip.compress(self.raw)
        self.meta = self.metadata()
        self.calls.clear()
        self.stage(["2012"], cache_directory=cache)
        self.assertEqual(2, len(self.calls))
        self.assertEqual(2, len(list(cache.glob("*.json.gz"))))

    def test_cache_symlinks_are_not_followed(self):
        outside = self.root / "outside"
        outside.mkdir()
        cache_link = self.root / "cache-link"
        cache_link.symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(FEEDS.FeedError, "symlink"):
            self.stage(["2012"], cache_directory=cache_link)
        self.assertFalse(list(outside.iterdir()))
        cache = self.root / "real-cache"
        cache.mkdir()
        original = outside / "original.gz"
        original.write_bytes(self.compressed)
        cached = FEEDS.cache_path(cache, FEEDS.parse_metadata(self.meta))
        cached.symlink_to(original)
        self.calls.clear()
        self.stage(["2012"], cache_directory=cache)
        self.assertEqual(2, len(self.calls))
        self.assertFalse(cached.is_symlink())
        self.assertEqual(self.compressed, original.read_bytes())


if __name__ == "__main__":
    unittest.main()
