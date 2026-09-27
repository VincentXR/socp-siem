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
import base64
import hashlib
import hmac
import json
import socket
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
PORTS = ROOT / "build" / "ports.env"
LOG_DIR = ROOT / ".cache" / "prod-boot"
STARTED = re.compile(r"\bStarted\s+\S+\s+in\s+[0-9.]+\s+seconds\b")
REQUIRED_ENV = (
    "SOCP_PG_RUNTIME_USER",
    "SOCP_PG_RUNTIME_PASSWORD",
    "SOCP_PG_MIGRATION_USER",
    "SOCP_PG_MIGRATION_PASSWORD",
    "SOCP_AUTH_SIGNING_JWK",
    "SOCP_AUTH_ISSUER",
    "SOCP_SECURITY_SERVICE_SECRET",
    "SOCP_SECURITY_METRICS_TOKEN",
)

# Integration jobs also exercise development profiles later in the same job,
# so their runner environment intentionally contains an HMAC secret. A
# production-context child must never inherit that fallback alongside JWKS;
# doing so would test an invalid mixed verifier configuration and should be
# rejected by ProdGuard.
DEVELOPMENT_AUTH_ENV = (
    "SOCP_JWT_SECRET",
    "SOCP_LOGIN_SECRET",
    "SOCP_SECURITY_ALLOW_PROD_HMAC",
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


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def production_environment(source: dict[str, str]) -> dict[str, str]:
    """Build an isolated asymmetric-auth environment for prod smoke children."""
    environment = source.copy()
    for name in DEVELOPMENT_AUTH_ENV:
        environment.pop(name, None)
    # The application datasource must use the restricted runtime role. Flyway
    # receives the separate migration role through the normal Spring property
    # mapping in each PostgreSQL profile.
    environment["SOCP_PG_USER"] = environment["SOCP_PG_RUNTIME_USER"]
    environment["SOCP_PG_PASSWORD"] = environment["SOCP_PG_RUNTIME_PASSWORD"]
    safe_defaults = {
        "SOCP_SECURITY_AUDIENCE": "socp-api",
        "SOCP_AUTH_ISSUER": environment["SOCP_AUTH_ISSUER"],
        "SOCP_SECURITY_ISSUER_URI": environment["SOCP_AUTH_ISSUER"],
        "SOCP_TENANT_RLS_ENABLED": "true",
        "SOCP_AUTH_COOKIE_SECURE": "true",
        "SOCP_RATELIMIT_BACKEND": "redis",
        "SOCP_RATELIMIT_FAIL_CLOSED": "true",
        "SOCP_AUDIT_SINK": "kafka",
        "SOCP_AUDIT_FAIL_CLOSED": "true",
        "SOCP_ALLOW_GLOBAL_INGEST_TOKEN": "false",
        "SOCP_DEMO_DATA_ENABLED": "false",
        "SOCP_SOAR_SIMULATION_ENABLED": "false",
        "SOCP_TEMPORAL_ENABLED": "true",
        "SOCP_SECURITY_REQUIRE_GATEWAY": "true",
    }
    for key, value in safe_defaults.items():
        environment.setdefault(key, value)
    return environment


def b64url(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def verify_gateway_session(port: int, environment: dict[str, str]) -> None:
    base = f"http://127.0.0.1:{port}"
    jwks = json.load(urllib.request.urlopen(base + "/.well-known/socp-jwks.json", timeout=5))
    keys = jwks.get("keys", [])
    if len(keys) != 1 or any(name in keys[0] for name in ("d", "p", "q", "dp", "dq", "qi")):
        raise RuntimeError("gateway JWKS did not expose exactly one public-only key")
    users = json.loads(environment["SOCP_AUTH_USERS"])
    username, password = next(iter(users.items()))
    request = urllib.request.Request(base + "/auth/login", method="POST",
        data=json.dumps({"username": username, "password": password}).encode(),
        headers={"Content-Type": "application/json"})
    response = urllib.request.urlopen(request, timeout=5)
    cookie = response.headers.get("Set-Cookie", "")
    match = re.search(r"SOCP_SESSION=([^;]+)", cookie)
    if not match:
        raise RuntimeError("gateway login did not return an HttpOnly platform session")
    encoded = match.group(1)
    head, payload, signature = encoded.split(".")
    header = json.loads(b64url(head))
    claims = json.loads(b64url(payload))
    key = keys[0]
    if header.get("alg") != "RS256" or header.get("kid") != key.get("kid"):
        raise RuntimeError("gateway session is not keyed RS256")
    if claims.get("iss") != environment["SOCP_AUTH_ISSUER"] or "socp-api" not in claims.get("aud", []):
        raise RuntimeError("gateway session issuer/audience does not match the platform contract")
    modulus = int.from_bytes(b64url(key["n"]), "big")
    exponent = int.from_bytes(b64url(key["e"]), "big")
    actual = pow(int.from_bytes(b64url(signature), "big"), exponent, modulus).to_bytes((modulus.bit_length() + 7) // 8, "big")
    digest_info = bytes.fromhex("3031300d060960864801650304020105000420") + hashlib.sha256(f"{head}.{payload}".encode()).digest()
    expected = b"\x00\x01" + b"\xff" * (len(actual) - len(digest_info) - 3) + b"\x00" + digest_info
    if not hmac.compare_digest(actual, expected):
        raise RuntimeError("gateway session signature does not verify against its published JWKS")


def start_one(java: str, service: str, timeout: int, environment: dict[str, str]) -> tuple[bool, str]:
    jar = jar_for(service)
    log_path = LOG_DIR / f"{service}.log"
    port = free_port() if service == "api-gateway" else 0
    service_environment = environment.copy()
    service_environment["SOCP_SECURITY_JWK_SET_URI"] = (
        f"http://127.0.0.1:{port}/.well-known/socp-jwks.json"
        if service == "api-gateway" else "http://127.0.0.1:9/unreachable-jwks")
    command = [
        java,
        "-Xms16m",
        "-Xmx256m",
        "-jar",
        str(jar),
        f"--server.port={port}",
        "--management.server.port=0",
        "--spring.profiles.active=prod,pg",
        "--spring.main.banner-mode=off",
    ]
    with log_path.open("wb") as output:
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            env=service_environment,
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
                    if service == "api-gateway":
                        verify_gateway_session(port, service_environment)
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

    environment = production_environment(dict(os.environ))

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
