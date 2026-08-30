#!/usr/bin/env python3
"""Replay one redacted Sysmon event through the authenticated ingest boundary."""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


def post(url, body, token, collector):
    request = urllib.request.Request(
        url,
        data=(json.dumps(body, ensure_ascii=False) + "\n").encode(),
        method="POST",
        headers={
            "Authorization": "Bearer " + token,
            "X-SOCP-Collector": collector,
            "Content-Type": "application/x-ndjson",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read().decode() or "{}")
    except urllib.error.HTTPError as error:
        return error.code, {"error": error.read().decode(errors="replace")}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample", type=Path, default=Path("agents/sysmon/sample-process-create.json"))
    parser.add_argument("--ingest-url", default=os.environ.get(
        "PIPELINE_INGEST_URL", "http://127.0.0.1:18081/search-config/api/v1/ingest"))
    parser.add_argument("--collector", default=os.environ.get("PIPELINE_COLLECTOR_ID", "sysmon-demo"))
    parser.add_argument("--token", default=os.environ.get("PIPELINE_COLLECTOR_TOKEN", ""))
    args = parser.parse_args()
    if not args.token:
        parser.error("--token or PIPELINE_COLLECTOR_TOKEN is required")
    sample = json.loads(args.sample.read_text(encoding="utf-8"))
    status, result = post(args.ingest_url, sample, args.token, args.collector)
    if status < 200 or status >= 300 or result.get("accepted", 0) < 1:
        print(json.dumps({"status": status, "response": result}, indent=2), file=sys.stderr)
        return 1
    print(json.dumps({"status": status, "accepted": result.get("accepted"),
                      "skipped": result.get("skipped", 0), "collector": args.collector}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
