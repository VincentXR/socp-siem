"""Load the repository's middleware image catalog."""

from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "infra" / "middleware-images.env"

SERVICE_IMAGE_KEYS = {
    "postgres": "SOCP_POSTGRES_IMAGE",
    "kafka": "SOCP_KAFKA_IMAGE",
    "opensearch": "SOCP_OPENSEARCH_IMAGE",
    "opensearch-dashboards": "SOCP_OPENSEARCH_DASHBOARDS_IMAGE",
    "redis": "SOCP_REDIS_IMAGE",
    "clickhouse": "SOCP_CLICKHOUSE_IMAGE",
    "minio": "SOCP_MINIO_IMAGE",
    "temporal": "SOCP_TEMPORAL_IMAGE",
    "temporal-ui": "SOCP_TEMPORAL_UI_IMAGE",
    "keycloak": "SOCP_KEYCLOAK_IMAGE",
    "prometheus": "SOCP_PROMETHEUS_IMAGE",
    "grafana": "SOCP_GRAFANA_IMAGE",
    "loki": "SOCP_LOKI_IMAGE",
    "jaeger": "SOCP_JAEGER_IMAGE",
}

FIXTURE_IMAGE_KEYS = {
    "proxy": "SOCP_PROXY_FIXTURE_IMAGE",
    "vector": "SOCP_VECTOR_IMAGE",
}

REQUIRED_KEYS = tuple(SERVICE_IMAGE_KEYS.values()) + tuple(FIXTURE_IMAGE_KEYS.values())


def _unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


def load_catalog(path: Path = CATALOG) -> dict[str, str]:
    """Read a Docker-compatible KEY=value catalog and fail closed on errors."""
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)=(.*)", line)
        if match is None:
            raise ValueError(f"invalid middleware image catalog line {line_number}: {raw_line}")
        key, value = match.groups()
        value = _unquote(value.strip())
        if not value:
            raise ValueError(f"empty middleware image for {key} at line {line_number}")
        if key in values:
            raise ValueError(f"duplicate middleware image key {key} at line {line_number}")
        values[key] = value

    missing = [key for key in REQUIRED_KEYS if key not in values]
    if missing:
        raise ValueError(f"middleware image catalog is missing: {', '.join(missing)}")
    return values


def image(service: str, catalog: dict[str, str] | None = None) -> str:
    """Return the catalog image for a Compose service or fixture."""
    key = SERVICE_IMAGE_KEYS.get(service) or FIXTURE_IMAGE_KEYS.get(service)
    if key is None:
        raise KeyError(f"unknown middleware image name: {service}")
    values = catalog if catalog is not None else load_catalog()
    try:
        return values[key]
    except KeyError as error:
        raise ValueError(f"middleware image catalog is missing: {key}") from error
