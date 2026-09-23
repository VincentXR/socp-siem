#!/usr/bin/env python3
"""Verify the runtime boundary around Spring Actuator endpoints.

The gateway deliberately exposes only ``/actuator/health`` without a user
credential.  All other actuator paths must be rejected before route lookup so
that an unrecognised or disabled endpoint cannot become an information leak.
This check is intentionally deployment-backed: it fails when the configured
gateway cannot be reached instead of turning an unavailable environment into a
successful skipped test.
"""

from __future__ import annotations

import argparse
import os
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class Probe:
    path: str
    expected: str


PROBES = (
    Probe("/actuator/info", "unauthenticated actuator info must be rejected"),
    Probe("/actuator/prometheus", "unauthenticated actuator metrics must be rejected"),
    Probe("/actuator/routes", "unauthenticated actuator routes must be rejected"),
)


def request_status(url: str, timeout: float) -> int:
    request = Request(url, method="GET", headers={"Accept": "application/json"})
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.status
    except HTTPError as error:
        # urllib raises for all non-2xx responses, which are exactly what the
        # negative probes are expected to observe.
        return error.code
    except (URLError, TimeoutError, OSError) as error:
        raise RuntimeError(f"{url}: {error}") from error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--url",
        default=os.environ.get("SOCP_GATEWAY_URL", "http://127.0.0.1:18092"),
        help="gateway base URL (defaults to SOCP_GATEWAY_URL)",
    )
    parser.add_argument("--timeout", type=float, default=5.0)
    args = parser.parse_args()
    base = args.url.rstrip("/")

    failures: list[str] = []
    for probe in PROBES:
        try:
            status = request_status(base + probe.path, args.timeout)
        except RuntimeError as error:
            failures.append(str(error))
            continue
        if status != 401:
            failures.append(f"{probe.path}: expected HTTP 401, got {status}")
        else:
            print(f"[PASS] {probe.path}: HTTP 401")

    try:
        health = request_status(base + "/actuator/health", args.timeout)
    except RuntimeError as error:
        failures.append(str(error))
    else:
        # Health is the sole public management probe.  It can legitimately be
        # 200 (UP) or 503 (dependency-aware DOWN/DEGRADED). A missing route,
        # permission error, or generic server failure does not prove probeability.
        if health not in (200, 503):
            failures.append(f"/actuator/health: expected HTTP 200 or 503, got {health}")
        else:
            print(f"[PASS] /actuator/health: HTTP {health} (public probe)")

    if failures:
        for failure in failures:
            print(f"[FAIL] {failure}")
        return 1
    print("Actuator authentication boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
