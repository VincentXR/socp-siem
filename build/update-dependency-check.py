#!/usr/bin/env python3
"""Retry transient NVD updates without publishing a partially written database."""

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRANSIENT_DOWNLOAD = re.compile(
    r"DownloadFailedException|Unable to download|Premature end|Unexpected end of file|"
    r"Connection (?:reset|closed)|Read timed out|SocketTimeoutException|"
    r"HTTP[^\n]*(?:429|502|503|504)", re.IGNORECASE
)
UPDATE_ERROR = re.compile(r"\[ERROR\]|Failed to process CVE-")
ANSI_ESCAPE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def run_logged(command: list[str], log_path: Path):
    with log_path.open("w", encoding="utf-8") as output:
        with subprocess.Popen(
            command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        ) as process:
            for line in process.stdout:
                output.write(line)
                output.flush()
                print(line, end="", flush=True)
            return process.wait()


def update_database(data: Path, logs: Path, command: list[str], attempts=3, retry_delay=15):
    data = data.resolve()
    logs.mkdir(parents=True, exist_ok=True)
    data.parent.mkdir(parents=True, exist_ok=True)
    # Keep the restored, previously successful cache untouched until a complete
    # update succeeds. Each retry gets a fresh JVM and the same clean baseline.
    with tempfile.TemporaryDirectory(prefix=".nvd-update-", dir=data.parent) as temporary:
        workspace = Path(temporary)
        for attempt in range(1, attempts + 1):
            staged = workspace / "database"
            if data.exists():
                shutil.copytree(data, staged)
            else:
                staged.mkdir()
            log_path = logs / f"update-attempt-{attempt}.log"
            print(f"NVD database update attempt {attempt}/{attempts}", flush=True)
            result = run_logged([*command, f"-DdataDirectory={staged}"], log_path)
            update_log = ANSI_ESCAPE.sub("", log_path.read_text(encoding="utf-8"))
            # Feed processors may log individual CVE import failures without
            # propagating them to the Maven exit status. Such a database is not
            # complete and must never replace the previously successful cache.
            if result == 0 and UPDATE_ERROR.search(update_log):
                print("Updater logged an import error despite exiting successfully.", file=sys.stderr)
                result = 1
            if result == 0:
                database = staged / "odc.mv.db"
                if not database.is_file() or database.stat().st_size == 0:
                    print("Update exited successfully without a vulnerability database.", file=sys.stderr)
                    return 1
                previous = workspace / "previous"
                if data.exists():
                    data.rename(previous)
                try:
                    staged.rename(data)
                except OSError:
                    if previous.exists():
                        previous.rename(data)
                    raise
                print("Complete NVD update published; report freshness still requires validation.")
                return 0
            transient = TRANSIENT_DOWNLOAD.search(update_log)
            shutil.rmtree(staged)
            if not transient or attempt == attempts:
                print("NVD update failed; restored cache was not modified or approved for scanning.",
                      file=sys.stderr)
                return result if result > 0 else 1
            print(f"Transient feed download failure; retrying in {retry_delay}s.", flush=True)
            time.sleep(retry_delay)
    return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-directory", type=Path, default=ROOT / ".cache/dependency-check")
    parser.add_argument("--log-directory", type=Path, default=ROOT / "target/dependency-audit")
    parser.add_argument("--feed-cache-directory", type=Path, default=ROOT / ".cache/nvd-feed-payloads")
    parser.add_argument("--attempts", type=int, choices=range(1, 4), default=3)
    args = parser.parse_args()
    args.log_directory.mkdir(parents=True, exist_ok=True)
    # Download and verify each official feed before the expensive DB import.
    # File URLs use the same upstream loader without flaky remote transfers
    # interrupting a partly imported database. Keep {0} literal for its formatter.
    with tempfile.TemporaryDirectory(prefix="nvd-feed-snapshot-") as temporary:
        feeds = Path(temporary) / "feeds"
        result = run_logged([
            sys.executable, str(ROOT / "build/nvd-feed-cache.py"),
            "--directory", str(feeds), "--log-directory", str(args.log_directory),
            "--cache-directory", str(args.feed_cache_directory),
        ], args.log_directory / "feeds.log")
        if result != 0:
            print("Official NVD feed snapshot failed validation; database update was not started.",
                  file=sys.stderr)
            return result if result > 0 else 1
        command = [
            "bash", str(ROOT / "build/mvnw.sh"), "-N",
            "org.owasp:dependency-check-maven:12.2.2:update-only",
            "-DfailOnError=true", "-DnvdValidForHours=0",
            "-DconnectionTimeout=30000", "-DreadTimeout=180000",
            f"-DnvdDatafeedUrl={feeds.as_uri()}/nvdcve-2.0-{{0}}.json.gz",
        ]
        return update_database(args.data_directory, args.log_directory, command, args.attempts)


if __name__ == "__main__":
    sys.exit(main())
