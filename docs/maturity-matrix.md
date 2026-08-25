# Service maturity matrix

Maturity is a product contract, not a claim that a local process is highly
available. `demo` means seeded or simulated behavior is part of the service;
`preview` means the boundary is usable but still needs an external integration
or operational hardening; `production-ready` means the repository owns the
durable transaction and verification boundary.

| Service | Maturity | Production contract |
|---|---|---|
| `api-gateway` | production-ready | OIDC/JWKS or explicitly approved HMAC, configured secrets and Redis for distributed limits |
| `search-config` | production-ready | PostgreSQL, Kafka, OpenSearch, Vector/collector credentials and bounded ingest requests |
| `detect-web` | production-ready | PostgreSQL/Kafka and durable journal/outbox; real rule content is operator-owned |
| `alert-web` | production-ready | PostgreSQL, Kafka and ClickHouse/notification dependencies are reachable |
| `incident-web` | production-ready | PostgreSQL is the source of truth for cases and timelines |
| `soc-base` | production-ready | PostgreSQL and platform-admin-only tenant mutation |
| `threat-web` | production-ready | PostgreSQL-backed IOC data; external feeds are optional inputs |
| `report-web` | preview | ClickHouse and optional object storage are required for complete production reports |
| `soar-web` | preview | Real connector URLs must be configured; unconfigured firewall/isolation/snapshot actions fail |
| `notify-web` | preview | Durable dispatch records exist, but email/SMS/webhook vendor delivery remains deployment-specific |
| `asset-web` | preview | Inventory and collection ingress are durable; CMDB/agent ownership is external |
| `hips-web` | preview | Endpoint events are durable; agent/Falco ownership is external |
| `detect-model` | preview | Secondary analysis endpoint is available; model lifecycle is not included in this repository |
| `attack-web` | preview | ATT&CK catalog and coverage APIs are available; content updates are operator-owned |
| `ai-assistant` | preview | LLM is optional; without an enabled client the service is a knowledge-base assistant, not an LLM |
| `asset-collect` | demo/compatibility | Standalone simulator is local-only and rejected by the `prod` profile |
| `hips-collect` | demo/compatibility | Standalone simulator is local-only and rejected by the `prod` profile |

The `local`, `integration`, and `prod` profile overlays enforce the most
important part of this contract: demo data and simulation are disabled by
production guards, while external response actions require a verified
connector acknowledgement. A maturity label does not replace HA, backup,
secret rotation, network policy, or vendor acceptance testing.
