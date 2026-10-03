#!/usr/bin/env python3
"""Check shipped scrape targets and exercise the file-token HTTP contract locally.

This does not run Prometheus or contact application/production endpoints.
"""
from __future__ import annotations

import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import secrets
import tempfile
from threading import Thread
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
TOKEN_PATH = "/run/secrets/socp_metrics_token"
PROD_TARGETS = {
    "search-config": {"search-config-api:8080", "search-config-worker:8080"},
    "detect-web": {"detect-web-api:8080", "detect-web-worker:8080"},
    "alert-web": {"alert-web:8080"},
}


def check_configuration(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    for filename in ("prometheus.yml", "prometheus-prod.yml"):
        text = (root / "infra/init-sql/prometheus" / filename).read_text(encoding="utf-8")
        jobs = re.split(r"(?m)^  - job_name:", text)[1:]
        if not jobs:
            errors.append(f"{filename}: no scrape jobs")
        for job in jobs:
            if "credentials:" in job or "${" in job:
                errors.append(f"{filename}: inline/placeholder token is forbidden")
            if f"credentials_file: {TOKEN_PATH}" not in job or "type: Bearer" not in job:
                errors.append(f"{filename}: each job must use the Bearer secret file")
        if "18092" in text or "targets: ['api-gateway" in text:
            errors.append(f"{filename}: gateway does not expose application metrics")
        if filename == "prometheus-prod.yml":
            observed = {}
            for job in jobs:
                name = job.splitlines()[0].strip().strip("'\"")
                targets = set(re.findall(r'[\'\"]([a-z0-9-]+:8080)[\'\"]', job))
                observed[name] = targets
                if f"metrics_path: /{name}/actuator/prometheus" not in job:
                    errors.append(f"{filename}: incorrect context path for {name}")
            if observed != PROD_TARGETS:
                errors.append(f"{filename}: production API/worker target set drifted")
        elif len(re.findall(r"targets:.*host.docker.internal:", text)) != 13:
            errors.append(f"{filename}: expected 13 development servlet targets")
    compose = (root / "infra/docker-compose.yml").read_text(encoding="utf-8")
    if not re.search(r"(?m)^secrets:\n  socp_metrics_token:\n    file:", compose):
        errors.append("base Compose must define the token as a file-backed secret")
    block = re.search(r"(?ms)^  prometheus:\n.*?(?=^  [a-zA-Z0-9_-]+:|\Z)", compose)
    if not block or not re.search(r"secrets:\s*\n\s*- socp_metrics_token", block.group()):
        errors.append("Prometheus must mount the token secret")
    prod = (root / "infra/docker-compose.prod.yml").read_text(encoding="utf-8")
    if "file: ${SOCP_METRICS_TOKEN_FILE:?" not in prod:
        errors.append("production Compose must require the token file explicitly")
    if "--config.file=/etc/prometheus/prometheus-prod.yml" not in prod:
        errors.append("production Compose must select the container-target scrape config")
    if "infra/secrets/" not in (root / ".gitignore").read_text(encoding="utf-8"):
        errors.append("local metrics token directory must be ignored")
    return errors


def verify_file_scrape() -> None:
    token = secrets.token_urlsafe(32)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            authenticated = hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {token}")
            status = 200 if authenticated and self.path == "/metrics" else 401
            self.send_response(status)
            self.end_headers()
            self.wfile.write(b"socp_metrics_contract 1\n" if status == 200 else b"unauthorized\n")

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="socp-metrics-") as directory:
            path = Path(directory) / "token"
            path.write_text(token, encoding="utf-8")
            path.chmod(0o600)
            url = f"http://127.0.0.1:{server.server_port}/metrics"
            for candidate, expected in ((path.read_text().strip(), 200),
                                        ("${SOCP_SECURITY_METRICS_TOKEN}", 401), ("wrong-token", 401), ("", 401)):
                headers = {"Authorization": f"Bearer {candidate}"} if candidate else {}
                try:
                    with urlopen(Request(url, headers=headers), timeout=3) as response:
                        actual = response.status
                        if expected == 200 and b"socp_metrics_contract 1" not in response.read():
                            raise AssertionError("authenticated response omitted metrics")
                except HTTPError as error:
                    actual = error.code
                if actual != expected:
                    raise AssertionError(f"file-token scrape returned {actual}, expected {expected}")
            path.unlink()
            try:
                path.read_text()
            except FileNotFoundError:
                pass
            else:
                raise AssertionError("missing token file must fail closed")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


def main() -> int:
    errors = check_configuration()
    if errors:
        for error in errors:
            print(f"[FAIL] {error}")
        return 1
    verify_file_scrape()
    print("Metrics authentication contract passed (13 dev / 5 production servlet targets; local file-token 200, wrong/missing/literal 401; no live Prometheus claim)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
