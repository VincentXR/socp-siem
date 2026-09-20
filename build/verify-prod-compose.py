#!/usr/bin/env python3
"""Fail-closed contract for the production-shaped orchestration artefacts.

Compose merges `environment` key by key, so reading
`infra/docker-compose.prod.yml` alone proves nothing about the configuration a
release actually runs. This gate therefore asserts the *effective* result of
`infra/docker-compose.yml` plus the overlay, and re-renders that merge with
`docker compose config` whenever the CLI is available, so a Compose merge rule
cannot drift away from the reader below.

Locked invariants:
  - every application service carries the whole ProdGuard prerequisite set
    (shared Redis limiter with fail-closed, Kafka audit sink with fail-closed,
    gateway-bound trust, collector identity where the guard demands it) and a
    Redis address it can actually reach from inside a container;
  - the secret-shaped variables are required from the environment (`:?`) rather
    than defaulted, because the file promises no development fallback;
  - the Redis instance that holds replay nonces and revoked sessions is
    non-evictable, and the production-shaped instance authenticates;
  - the broker in the merged result keeps no undeclared development listener,
    and every inherited broker posture key is restated by the overlay; the
    broker must not create topics, so the provisioning script has to cover the
    whole main chain plus every dead-letter queue the consumers derive;
  - the Kubernetes baseline (deploy/helm/socp-core) that this overlay rehearses
    for keeps the same fail-closed shape: startup/liveness probes on a limited
    health group, a readiness budget that covers the pooled-connection wait it
    contains, and an explicit migration-role contract per database workload.

This is a configuration contract, not a claim that either shape has been
booted; executable boot evidence is build/verify-prod-boot.py.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASE_COMPOSE = ROOT / "infra" / "docker-compose.yml"
PROD_COMPOSE = ROOT / "infra" / "docker-compose.prod.yml"
IMAGE_CATALOG = ROOT / "infra" / "middleware-images.env"
HELM_VALUES = ROOT / "deploy" / "helm" / "socp-core" / "values.yaml"
HELM_DEPLOYMENTS = ROOT / "deploy" / "helm" / "socp-core" / "templates" / "deployments.yaml"
CREATE_TOPICS = ROOT / "infra" / "init-sql" / "kafka" / "create-topics.sh"

# Topic names the running code actually produces to or consumes from, taken from
# services/*/src/main/java (@Value defaults and *Properties fields). Each entry
# also derives a `<topic>-dlq` dead-letter queue at the consumer's hand-off.
KAFKA_MAIN_TOPICS = (
    "socp-events",
    "socp-detection-routed-v2",
    "socp-alarm-events",
    "socp-alarm-original",
    "socp-rule-changes",
    "socp-audit",
)
KAFKA_DLQ_TOPICS = tuple(f"{topic}-dlq" for topic in KAFKA_MAIN_TOPICS)

APP_SERVICES = (
    "api-gateway",
    "search-config-api",
    "search-config-worker",
    "detect-web-api",
    "detect-web-worker",
    "alert-web",
)
SERVLET_SERVICES = APP_SERVICES[1:]
# Keys ProdGuard rejects the boot over, plus the Redis wiring those keys point
# at. The map value is the literal string that must appear in the effective
# configuration; COMMON_SECRETS covers the ones that must be interpolated.
COMMON_ENV = {
    "SPRING_PROFILES_ACTIVE": "prod",
    "SERVER_PORT": "8080",
    "SOCP_RATELIMIT_BACKEND": "redis",
    "SOCP_RATELIMIT_FAIL_CLOSED": "true",
    "SOCP_AUDIT_SINK": "kafka",
    "SOCP_AUDIT_FAIL_CLOSED": "true",
    "SOCP_SECURITY_REQUIRE_GATEWAY": "true",
    "SOCP_TENANT_RLS_ENABLED": "true",
    "SPRING_DATA_REDIS_HOST": "redis",
    "SPRING_DATA_REDIS_PORT": "6379",
}
COMMON_SECRETS = {
    # environment key -> interpolation variable
    "SOCP_SECURITY_ISSUER_URI": "SOCP_SECURITY_ISSUER_URI",
    "SOCP_SECURITY_JWK_SET_URI": "SOCP_SECURITY_JWK_SET_URI",
    "SOCP_SECURITY_AUDIENCE": "SOCP_SECURITY_AUDIENCE",
    "SOCP_SECURITY_SERVICE_SECRET": "SOCP_SECURITY_SERVICE_SECRET",
    "SOCP_SECURITY_METRICS_TOKEN": "SOCP_SECURITY_METRICS_TOKEN",
    "SPRING_DATA_REDIS_PASSWORD": "SOCP_REDIS_PASSWORD",
}
PG_ROLE_ENV = {
    "SOCP_PG_USER": "SOCP_PG_RUNTIME_USER",
    "SOCP_PG_PASSWORD": "SOCP_PG_RUNTIME_PASSWORD",
    "SOCP_PG_MIGRATION_USER": "SOCP_PG_MIGRATION_USER",
    "SOCP_PG_MIGRATION_PASSWORD": "SOCP_PG_MIGRATION_PASSWORD",
}
COLLECTOR_ENV = {
    "SOCP_COLLECTOR_CREDENTIALS": "SOCP_COLLECTOR_CREDENTIALS",
    "SOCP_INGEST_TOKEN": "SOCP_INGEST_TOKEN",
    "SOCP_VECTOR_TOKEN": "SOCP_VECTOR_TOKEN",
}

# Broker keys whose development value would ride into the merged result through
# the same key-by-key merge if the overlay did not restate them.
KAFKA_DECLARED_KEYS = (
    "KAFKA_LISTENERS",
    "KAFKA_ADVERTISED_LISTENERS",
    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
    "KAFKA_AUTO_CREATE_TOPICS_ENABLE",
    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR",
    "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR",
    "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR",
)


def unquote(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def parse_compose(text: str) -> dict:
    """Parse the YAML subset both Compose files are written in.

    Block mappings, block sequences, and inline flow values kept as raw
    strings. Comments and anchors are ignored because nothing here asserts on
    them; the reader exists to model Compose's merge, not to be a YAML library.
    """
    lines: list[tuple[int, str]] = []
    for raw in text.splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("---"):
            continue
        lines.append((len(raw) - len(raw.lstrip(" ")), stripped))

    def parse_mapping(position: int, indent: int) -> tuple[dict, int]:
        mapping: dict = {}
        while position < len(lines):
            current_indent, content = lines[position]
            if current_indent < indent:
                break
            if content.startswith("- "):
                position += 1
                continue
            key, _, inline = content.partition(":")
            key = unquote(key)
            inline = inline.strip()
            position += 1
            if inline:
                mapping[key] = unquote(inline)
                continue
            if position < len(lines) and lines[position][1].startswith("- "):
                sequence, position = parse_sequence(position, lines[position][0])
                mapping[key] = sequence
                continue
            if position < len(lines) and lines[position][0] > indent:
                nested, position = parse_mapping(position, lines[position][0])
                mapping[key] = nested
                continue
            mapping[key] = ""
        return mapping, position

    def parse_sequence(position: int, indent: int) -> tuple[list[str], int]:
        items: list[str] = []
        while position < len(lines):
            current_indent, content = lines[position]
            if current_indent != indent or not content.startswith("- "):
                break
            items.append(unquote(content[2:]))
            position += 1
        return items, position

    document, _ = parse_mapping(0, 0)
    return document


def merge_compose(base: dict, overlay: dict) -> dict:
    """Model Compose's merge: mappings merge by key, sequences replace, except
    `ports`, which Compose appends."""
    if not isinstance(base, dict) or not isinstance(overlay, dict):
        return overlay
    merged = dict(base)
    for key, value in overlay.items():
        if key == "ports" and isinstance(value, list) and isinstance(merged.get(key), list):
            merged[key] = list(merged[key]) + list(value)
        elif isinstance(value, dict) or isinstance(merged.get(key), dict):
            merged[key] = merge_compose(merged.get(key, {}), value if isinstance(value, dict) else {})
        else:
            merged[key] = value
    return merged


def services_of(document: dict) -> dict:
    return document.get("services", {}) if isinstance(document, dict) else {}


def environment_of(document: dict, service: str) -> dict:
    block = services_of(document).get(service)
    if not isinstance(block, dict):
        return {}
    environment = block.get("environment", {})
    if isinstance(environment, dict):
        return {str(key): str(value) for key, value in environment.items()}
    # Sequence form: - KEY=value
    parsed: dict[str, str] = {}
    if isinstance(environment, list):
        for item in environment:
            name, _, value = str(item).partition("=")
            parsed[name] = value
    return parsed


def require_literal(errors: list[str], service: str, env: dict, key: str, expected: str, source: str) -> None:
    value = env.get(key)
    if value is None:
        errors.append(f"{source}: {service} must set {key}={expected}")
    elif value != expected:
        errors.append(f"{source}: {service} has {key}={value!r}, expected {expected!r}")


def require_required_secret(errors: list[str], service: str, env: dict, key: str, variable: str, source: str, rendered: bool) -> None:
    value = env.get(key)
    if value is None:
        errors.append(f"{source}: {service} must set {key} from ${{{variable}:?}}")
    elif rendered:
        if not value.strip():
            errors.append(f"{source}: {service} has an empty {key}")
    elif not re.match(rf"^\$\{{{variable}:\?", value):
        errors.append(f"{source}: {service} {key} must be required from the environment, got {value!r}")


def check_application_services(errors: list[str], document: dict, source: str, rendered: bool) -> None:
    for service in APP_SERVICES:
        env = environment_of(document, service)
        if not env:
            errors.append(f"{source}: {service} has no environment block")
            continue
        for key, expected in COMMON_ENV.items():
            require_literal(errors, service, env, key, expected, source)
        for key, variable in COMMON_SECRETS.items():
            require_required_secret(errors, service, env, key, variable, source, rendered)
        if service in SERVLET_SERVICES:
            # Every servlet service in this overlay has a PostgreSQL datasource
            # and therefore the two-role contract.
            for key, variable in PG_ROLE_ENV.items():
                require_required_secret(errors, service, env, key, variable, source, rendered)
            require_literal(errors, service, env, "SOCP_KAFKA_BOOTSTRAP", "kafka:9092", source)
        if service in ("search-config-api", "search-config-worker"):
            # ProdGuard keys these rules on spring.application.name, which both
            # search-config processes share.
            for key, variable in COLLECTOR_ENV.items():
                require_required_secret(errors, service, env, key, variable, source, rendered)
            require_literal(errors, service, env, "SOCP_ALLOW_GLOBAL_INGEST_TOKEN", "false", source)
        if service == "api-gateway":
            require_literal(errors, service, env, "SOCP_AUTH_COOKIE_SECURE", "true", source)
            require_literal(errors, service, env, "SOCP_AUTH_REVOCATION_BACKEND", "redis", source)
            require_literal(errors, service, env, "SOCP_OIDC_STATE_BACKEND", "redis", source)
            require_required_secret(errors, service, env, "SOCP_LOGIN_SECRET", "SOCP_LOGIN_SECRET", source, rendered)
            for key in ("SOCP_SSA_URI", "SOCP_GLS_URI", "SOCP_GAS_WEB_URI", "SOCP_GAS_WORKER_URI"):
                if not env.get(key, "").strip():
                    errors.append(f"{source}: api-gateway must route {key}")


def check_kafka(errors: list[str], base: dict, overlay: dict, effective: dict) -> None:
    base_env = environment_of(base, "kafka")
    overlay_env = environment_of(overlay, "kafka")
    effective_env = environment_of(effective, "kafka")
    for key in KAFKA_DECLARED_KEYS:
        if key not in overlay_env:
            errors.append(f"docker-compose.prod.yml: kafka must declare {key} instead of inheriting {base_env.get(key, '')!r}")
    listeners = effective_env.get("KAFKA_LISTENERS", "")
    protocol_map = effective_env.get("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "")
    advertised = effective_env.get("KAFKA_ADVERTISED_LISTENERS", "")
    for name, value in (("KAFKA_LISTENERS", listeners), ("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", protocol_map),
                        ("KAFKA_ADVERTISED_LISTENERS", advertised)):
        if "PLAINTEXT_KIND" in value:
            errors.append(f"effective kafka {name} keeps the development-only PLAINTEXT_KIND listener: {value}")
    for listener in (part.strip() for part in listeners.split(",") if part.strip()):
        match = re.match(r"^[A-Z_]+://:([0-9]+)$", listener)
        if match is None or match.group(1) not in {"9092", "9093"}:
            errors.append(f"effective kafka KAFKA_LISTENERS must bind only 9092/9093, got {listener}")
    if effective_env.get("KAFKA_AUTO_CREATE_TOPICS_ENABLE") != "false":
        errors.append(
            "effective kafka must keep auto-create false: every topic is provisioned by "
            "infra/init-sql/kafka/create-topics.sh, and a lazily created topic hides a typo "
            "or a missing dead-letter queue behind a healthy-looking broker"
        )
    if not CREATE_TOPICS.is_file():
        errors.append("infra/init-sql/kafka/create-topics.sh is missing while auto-create is off")
    else:
        script = CREATE_TOPICS.read_text(encoding="utf-8")
        if "--if-not-exists" not in script:
            errors.append("create-topics.sh must be idempotent (--if-not-exists): a rerun after "
                          "a partial failure has to converge, not abort the rehearsal")
        declared = set(re.findall(r"(?m)^\s*(socp-[a-z0-9-]+)\b", re.sub(r"(?m)#.*$", "", script)))
        for topic in (*KAFKA_MAIN_TOPICS, *KAFKA_DLQ_TOPICS):
            if topic not in declared:
                errors.append(f"create-topics.sh must pre-create {topic} before auto-create is off")
        for topic in sorted(declared - set(KAFKA_MAIN_TOPICS) - set(KAFKA_DLQ_TOPICS)):
            errors.append(f"create-topics.sh provisions {topic}, which no code produces to or consumes from")


def check_redis(errors: list[str], base: dict, overlay: dict, effective: dict) -> None:
    for source, document in (("infra/docker-compose.yml", base), ("docker-compose.prod.yml", overlay)):
        command = services_of(document).get("redis", {}).get("command", "") if isinstance(services_of(document).get("redis", {}), dict) else ""
        policy = re.search(r"--maxmemory-policy[\"',\s]+([a-z-]+)", str(command))
        if policy is None:
            errors.append(f"{source}: redis must set an explicit --maxmemory-policy")
        elif policy.group(1) != "noeviction":
            errors.append(
                f"{source}: redis uses --maxmemory-policy {policy.group(1)}; replay nonces and revoked "
                "sessions must not be evictable - an evicted key makes SETNX succeed again"
            )
    overlay_command = str(services_of(overlay).get("redis", {}).get("command", ""))
    if "--requirepass" not in overlay_command:
        errors.append("docker-compose.prod.yml: redis must require a password; the merged instance carries correctness keys")
    elif "SOCP_REDIS_PASSWORD:?" not in overlay_command.replace(" ", ""):
        errors.append("docker-compose.prod.yml: redis --requirepass must come from ${SOCP_REDIS_PASSWORD:?}, not a literal")
    effective_command = str(services_of(effective).get("redis", {}).get("command", ""))
    if "--requirepass" not in effective_command or "noeviction" not in effective_command:
        errors.append("effective redis command lost requirepass or noeviction during the merge")
    healthcheck = str(services_of(overlay).get("redis", {}).get("healthcheck", ""))
    if "-a" not in healthcheck:
        errors.append("docker-compose.prod.yml: the redis healthcheck must authenticate, or it fails closed on an instance that is up")


def check_helm_parity(errors: list[str]) -> None:
    if not HELM_VALUES.is_file() or not HELM_DEPLOYMENTS.is_file():
        errors.append("missing the Helm baseline that this overlay rehearses")
        return
    values = HELM_VALUES.read_text(encoding="utf-8")
    deployments = HELM_DEPLOYMENTS.read_text(encoding="utf-8")

    def probe_path(name: str) -> str | None:
        # Explanatory comments are allowed between the probe key and its path.
        match = re.search(
            rf"(?ms){name}Probe:\s*$\s*(?:#[^\n]*\s*)*httpGet:\s*$\s*(?:#[^\n]*\s*)*path:\s*(.+?)\s*$",
            deployments,
        )
        return match.group(1) if match else None

    startup = probe_path("startup")
    if startup is None:
        errors.append("Helm deployments.yaml must render a startupProbe httpGet path")
    elif not re.search(r"/(liveness|startup)$", startup):
        errors.append(
            f"Helm startupProbe path {startup!r} is the aggregated health endpoint: a dependency "
            "outage would keep a new container from ever passing startup and restart-loop it"
        )
    for group in ("readiness", "liveness"):
        path = probe_path(group)
        if path is None or not path.endswith(f"/{group}"):
            errors.append(f"Helm {group}Probe must target the {group} health group")

    readiness_timeout = re.search(r"(?ms)readinessProbe:.*?timeoutSeconds:\s*([0-9]+)", deployments)
    hikari = re.search(r"(?m)^\s*SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT:\s*\"?([0-9]+)", values)
    if readiness_timeout is None or hikari is None:
        errors.append("Helm readiness must pin the pooled-connection wait inside the probe timeout")
    elif int(hikari.group(1)) >= int(readiness_timeout.group(1)) * 1000:
        errors.append(
            f"SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT={hikari.group(1)}ms is not below the "
            f"readiness timeout of {readiness_timeout.group(1)}s; the `db` contributor would surface as "
            "a probe timeout instead of a DOWN signal"
        )

    runtime_config = values.split("\nworkloads:\n", 1)[0]
    for marker in ("SOCP_RATELIMIT_BACKEND: redis", 'SOCP_RATELIMIT_FAIL_CLOSED: "true"',
                   "SPRING_DATA_REDIS_HOST: redis.socp-data.svc.cluster.local",
                   "SOCP_AUDIT_SINK: kafka", 'SOCP_AUDIT_FAIL_CLOSED: "true"'):
        if marker not in runtime_config:
            errors.append(f"Helm runtime.config lacks production marker: {marker}")

    workloads_tail = values.split("\nworkloads:\n", 1)[-1]
    for workload in ("search-config-api", "search-config-worker", "detect-web-api", "detect-web-worker", "alert-web"):
        block = re.search(rf"(?ms)^  {re.escape(workload)}:\s*$.*?(?=^  [a-z0-9-]+:\s*$|\Z)", workloads_tail)
        if block is None:
            errors.append(f"Helm values omit workload {workload}")
            continue
        # application-pg.yml binds Flyway's pair without defaults
        # (${SOCP_PG_MIGRATION_USER}), so a missing key CrashLoops the app; the
        # runtime pair still carries ${SOCP_PG_USER:socp}-style fallbacks.
        # envFrom accepts any subset of Secret keys, so only an explicit
        # secretKeyRef fails the Pod at CreateContainerConfigError before the
        # container starts with the intended, operator-managed credentials.
        for key in ("SOCP_PG_USER", "SOCP_PG_PASSWORD", "SOCP_PG_MIGRATION_USER", "SOCP_PG_MIGRATION_PASSWORD"):
            if re.search(rf"(?m)^\s+{key}:\s*{key}\s*$", block.group(0)) is None:
                errors.append(f"Helm workload {workload} must pin {key} through secretEnv")
    gateway_block = re.search(r"(?ms)^  api-gateway:\s*$.*?(?=^  [a-z0-9-]+:\s*$|\Z)", workloads_tail)
    if gateway_block is None:
        errors.append("Helm values omit workload api-gateway")
    else:
        for marker in ("SOCP_AUTH_REVOCATION_BACKEND: redis", "SOCP_OIDC_STATE_BACKEND: redis"):
            if marker not in gateway_block.group(0):
                errors.append(f"Helm api-gateway must declare {marker}")
    # Readiness must not chain transitive downstream services: the detect API
    # never calls alert-web on any request path.
    detect_api = re.search(r"(?ms)^  detect-web-api:\s*$.*?(?=^  [a-z0-9-]+:\s*$|\Z)", workloads_tail)
    if detect_api and re.search(r"SOCP_HEALTH_REQUIRED_ENDPOINTS:[^\n]*alert-web", detect_api.group(0)):
        errors.append("Helm detect-web-api readiness must not depend on alert-web (no synchronous call exists)")


def compose_binary() -> list[str] | None:
    docker = shutil.which("docker")
    if docker is None:
        return None
    probe = subprocess.run([docker, "compose", "version"], capture_output=True, text=True, check=False)
    return [docker, "compose"] if probe.returncode == 0 else None


def render_effective(command: list[str]) -> tuple[str | None, str]:
    environment = dict(os.environ)
    if IMAGE_CATALOG.is_file():
        for line in IMAGE_CATALOG.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                name, _, value = line.partition("=")
                environment.setdefault(name.strip(), value.strip())
    text = BASE_COMPOSE.read_text(encoding="utf-8") + PROD_COMPOSE.read_text(encoding="utf-8")
    for match in re.finditer(r"\$\{([A-Z0-9_]+)([^}]*)", text):
        name, rest = match.group(1), match.group(2)
        # Only variables with no default are supplied: the documented defaults
        # are part of the contract under test.
        if rest.startswith(":?") or rest == "":
            environment.setdefault(name, "verify-prod-compose")
    result = subprocess.run(
        [*command, "-f", str(BASE_COMPOSE.relative_to(ROOT)), "-f", str(PROD_COMPOSE.relative_to(ROOT)), "config"],
        cwd=ROOT, env=environment, capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        return None, (result.stdout + result.stderr).strip()
    return result.stdout, ""


def main() -> int:
    errors: list[str] = []
    if not BASE_COMPOSE.is_file() or not PROD_COMPOSE.is_file():
        print("[FAIL] production Compose files are missing", file=sys.stderr)
        return 1

    base = parse_compose(BASE_COMPOSE.read_text(encoding="utf-8"))
    overlay = parse_compose(PROD_COMPOSE.read_text(encoding="utf-8"))
    effective = merge_compose(base, overlay)

    check_application_services(errors, effective, "effective Compose", False)
    check_kafka(errors, base, overlay, effective)
    check_redis(errors, base, overlay, effective)
    check_helm_parity(errors)

    rendered_note = "static merge model"
    command = compose_binary()
    if command is None:
        print("[info] docker compose CLI unavailable; asserting the built-in merge model only", file=sys.stderr)
    else:
        rendered, failure = render_effective(command)
        if rendered is None:
            errors.append(f"`docker compose config` failed for base+prod: {failure}")
        else:
            document = parse_compose(rendered)
            check_application_services(errors, document, "rendered Compose", True)
            rendered_note = "docker compose config render"

    if errors:
        print("Production-shaped orchestration contract failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(
        "Production-shaped orchestration contract passed "
        f"({len(APP_SERVICES)} application services, Redis noeviction+auth, declared Kafka posture, "
        f"Helm probe and migration-role parity; verified by {rendered_note})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
