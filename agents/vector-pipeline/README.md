# Vector pipeline

This directory contains the Vector edge-agent configuration used to forward
logs to SOCP `search-config`.

## Responsibility

Vector owns collection, transport, lightweight envelope metadata, disk
buffering, and retry. It does not implement security semantics, rule matching,
alerting, storage, or AI. Those responsibilities stay in SOCP so the same
canonical event path is used by Vector and direct-ingest verification.

## Data path

```text
file / syslog / kafka source
    -> normalize envelope (collector_host, ingested_at, parse_format)
    -> NDJSON POST /search-config/api/v1/ingest
```

`search-config` parses the payload, adds canonical routing fields, and
publishes `socp-events` to Kafka. The `collector_host` field distinguishes
multiple agents without requiring service-specific code changes.

## Run locally

```bash
# Start the core path (search-config is normally on port 18081)
bash build/run-all.sh start core

# Build/verify the patched Linux image locally, then create a new collector
bash build/vector-runtime.sh image
bash build/vector-runtime.sh verify-image
bash build/run-vector.sh start
```

Run these commands from the repository root. The local image is pinned in
`infra/middleware-images.env` and includes both required disk-buffer backports.
On Windows use Docker Desktop Linux containers through Git Bash or WSL; a
patched native `vector.exe` is not validated and is not an automatic fallback.
See [runtime provenance, validation, upgrade and rollback](../../docs/operations/vector-runtime.md).

The helper refuses to replace an existing collector. `stop` now keeps the
container and disk state. Preserve the existing configuration and data directory
before an upgrade; the helper does not migrate state or create a persistent
state volume for an old installation.

The exact generated configuration is managed through the Search source APIs:

```text
POST /search-config/api/v1/sources
POST /search-config/api/v1/render
```

The standard configuration keeps `healthcheck.enabled`, JSON plus
`newline_delimited`, disk buffering, retry, and multiline handling explicit.
These settings prevent agent startup failures and preserve events while
`search-config` is restarting.
