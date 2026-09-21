#!/usr/bin/env python3
"""Generate disposable job credentials in GitHub's environment file, never the checkout."""

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import secrets


def credentials(scope):
    jwt = secrets.token_hex(32)
    values = {"SOCP_JWT_SECRET": jwt, "SOCP_LOGIN_SECRET": jwt}
    if scope == "build":
        return values

    for name in (
        "SOCP_SECURITY_SERVICE_SECRET", "SOCP_SECURITY_METRICS_TOKEN",
        "SOCP_PG_BOOTSTRAP_PASSWORD", "SOCP_PG_RUNTIME_PASSWORD",
        "SOCP_PG_MIGRATION_PASSWORD", "SOCP_CK_PASSWORD", "SOCP_MINIO_SECRET",
        "SOCP_CI_TRUSTSTORE_PASSWORD",
    ):
        values[name] = secrets.token_hex(32)
    values["SOCP_PG_PASSWORD"] = values["SOCP_PG_RUNTIME_PASSWORD"]
    # OpenSearch's bootstrap validator requires mixed character classes.
    values["SOCP_OPENSEARCH_PASSWORD"] = "Aa1!" + secrets.token_hex(32)
    values["PIPELINE_OS_AUTH"] = "admin:" + values["SOCP_OPENSEARCH_PASSWORD"]
    values["PIPELINE_CK_AUTH"] = "default:" + values["SOCP_CK_PASSWORD"]
    collector = secrets.token_hex(32)
    expires = (datetime.now(timezone.utc) + timedelta(days=1)).isoformat()
    values["SOCP_COLLECTOR_CREDENTIALS"] = f"ci-chaos|default|{collector}|{expires}"
    for name in ("PIPELINE_COLLECTOR_TOKEN", "SOCP_INGEST_TOKEN", "SOCP_VECTOR_TOKEN"):
        values[name] = collector
    users = {user: secrets.token_hex(32) for user in ("admin", "demo", "viewer")}
    values["SOCP_AUTH_USERS"] = json.dumps(users, separators=(",", ":"))
    values["SOCP_AUTH_ROLES"] = json.dumps(
        {"admin": "admin", "demo": "analyst", "viewer": "viewer"}, separators=(",", ":"))
    values["DEMO_PASS"] = users["demo"]
    values["PIPELINE_PASS"] = users["demo"]
    values["SOAR_VERIFY_PASSWORD"] = users["admin"]
    values["RULE_VERIFY_PASSWORD"] = users["admin"]
    return values


def write_environment(values, destination):
    # Mask individual passwords as well as composite values before any later
    # step can print its environment. The environment file is runner-owned.
    masks = set(values.values())
    masks.update(json.loads(values.get("SOCP_AUTH_USERS", "{}")).values())
    for value in sorted(masks):
        print(f"::add-mask::{value}")
    with destination.open("a", encoding="utf-8") as output:
        for name, value in values.items():
            output.write(f"{name}={value}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=("build", "integration"), required=True)
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") != "true" or not os.environ.get("GITHUB_ENV"):
        parser.error("requires a GitHub Actions job and its GITHUB_ENV file")
    write_environment(credentials(args.scope), Path(os.environ["GITHUB_ENV"]))


if __name__ == "__main__":
    main()
