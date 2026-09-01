#!/usr/bin/env python3
"""Start packaged services with prod,pg and require a complete Spring context.

The caller owns middleware and credentials. Each JAR is started on an ephemeral
port, observed until Spring emits its Started marker, and then stopped before
the next service. Logs remain under .cache/prod-boot for CI evidence.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
PORTS = ROOT / "build" / "ports.env"
LOG_DIR = ROOT / ".cache" / "prod-boot"
STARTED = re.compile(r"\bStarted\s+\S+\s+in\s+[0-9.]+\s+seconds\b")
REQUIRED_ENV = (
    "SOCP_PG_PASSWORD",
    "SOCP_JWT_SECRET",
    "SOCP_LOGIN_SECRET",
    "SOCP_SECURITY_SERVICE_SECRET",
    "SOCP_SECURITY_METRICS_TOKEN",
)


def executable_modules() -> list[str]:
    text = PORTS.read_text(encoding="utf-8")
    match = re.search(r'^SOCP_MODULE_NAMES="([^"]+)"$', text, re.MULTILINE)
    if not match:
        raise RuntimeError("build/ports.env does not define SOCP_MODULE_NAMES")
    return match.group(1).split()


def jar_for(service: str) -> Path:
    return ROOT / "services" / service / "target" / f"{service}-1.0.0-SNAPSHOT.jar"


def stop(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def start_one(java: str, service: str, timeout: int, environment: dict[str, str]) -> tuple[bool, str]:
    jar = jar_for(service)
    log_path = LOG_DIR / f"{service}.log"
    command = [
        java,
        "-Xms16m",
        "-Xmx256m",
        "-jar",
        str(jar),
        "--server.port=0",
        "--management.server.port=0",
        "--spring.profiles.active=prod,pg",
        "--spring.main.banner-mode=off",
    ]
    with log_path.open("wb") as output:
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            env=environment,
            stdout=output,
            stderr=subprocess.STDOUT,
        )
        deadline = time.monotonic() + timeout
        observed = ""
        try:
            while time.monotonic() < deadline:
                output.flush()
                observed = log_path.read_text(encoding="utf-8", errors="replace")
                if STARTED.search(observed):
                    return True, f"started ({log_path.relative_to(ROOT)})"
                exit_code = process.poll()
                if exit_code is not None:
                    return False, f"exited with code {exit_code} ({log_path.relative_to(ROOT)})"
                time.sleep(0.25)
            return False, f"timed out after {timeout}s ({log_path.relative_to(ROOT)})"
        finally:
            stop(process)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--services", nargs="+", help="service module names; defaults to every executable module")
    parser.add_argument("--timeout", type=int, default=75, help="per-service startup timeout in seconds")
    args = parser.parse_args()

    known = executable_modules()
    services = args.services or known
    unknown = sorted(set(services) - set(known))
    if unknown:
        print(f"Unknown executable services: {', '.join(unknown)}", file=sys.stderr)
        return 2
    missing_env = [name for name in REQUIRED_ENV if not os.environ.get(name, "").strip()]
    if missing_env:
        print(f"Production boot smoke requires explicit environment: {', '.join(missing_env)}", file=sys.stderr)
        return 2
    missing_jars = [str(jar_for(service).relative_to(ROOT)) for service in services if not jar_for(service).is_file()]
    if missing_jars:
        print(f"Missing packaged JARs: {', '.join(missing_jars)}", file=sys.stderr)
        return 2
    java = shutil.which("java")
    if java is None:
        print("java is not available on PATH", file=sys.stderr)
        return 2

    environment = os.environ.copy()
    safe_defaults = {
        "SOCP_SECURITY_AUDIENCE": "socp-api",
        "SOCP_SECURITY_ALLOW_PROD_HMAC": "true",
        "SOCP_TENANT_RLS_ENABLED": "true",
        "SOCP_AUTH_COOKIE_SECURE": "true",
        "SOCP_RATELIMIT_BACKEND": "redis",
        "SOCP_RATELIMIT_FAIL_CLOSED": "true",
        "SOCP_AUDIT_SINK": "kafka",
        "SOCP_AUDIT_FAIL_CLOSED": "true",
        "SOCP_ALLOW_GLOBAL_INGEST_TOKEN": "false",
        "SOCP_DEMO_DATA_ENABLED": "false",
        "SOCP_SOAR_SIMULATION_ENABLED": "false",
        "SOCP_ASSET_COLLECT_SIMULATION_ENABLED": "false",
        "SOCP_HIPS_COLLECT_SIMULATION_ENABLED": "false",
        "SOCP_TEMPORAL_ENABLED": "true",
    }
    for key, value in safe_defaults.items():
        environment.setdefault(key, value)

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    failures: list[str] = []
    for service in services:
        ok, detail = start_one(java, service, max(10, args.timeout), environment)
        print(f"[{'PASS' if ok else 'FAIL'}] {service}: {detail}")
        if not ok:
            failures.append(service)

    if failures:
        print(f"Production context smoke failed: {', '.join(failures)}", file=sys.stderr)
        return 1
    print(f"Production context smoke passed: {len(services)} packaged services")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
