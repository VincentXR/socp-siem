# Demo and Portfolio Checklist

Use this checklist when preparing a resume link, interview walkthrough, or
release. It keeps the demo focused on evidence instead of the number of
services.

## 90-second walkthrough

1. Start the integration stack and open the workbench.
2. Show the architecture diagram and explain the canonical event contract.
3. Run `python build/demos/attack-scenarios.py`.
4. Show the alert with severity, risk score, IOC/ATT&CK enrichment and the
   generated incident.
5. Open the rule editor, change or add a rule, and show that the detection
   engine reloads without a restart.
6. Open the trace/metrics view or CI result and point out the Kafka → detection
   → PostgreSQL/OpenSearch/ClickHouse assertions.

## Suggested evidence to capture

- Workbench overview with alert counts and risk distribution.
- Alert details with evidence, ATT&CK mapping and disposition controls.
- Detection rule lifecycle or hot-reload result.
- Incident timeline and SOAR execution state.
- Jaeger trace or CI `e2e-pipeline` result.

Do not include real credentials, private hostnames, customer data, or local
database files in screenshots. Demo credentials in the README are local-only.

## Resume claims to keep precise

- Say “at-least-once delivery with idempotency and replay paths”, not “exactly
  once”.
- Say “production-oriented self-hosted platform”, not “production SIEM”.
- Call out logical tenant isolation and the in-process detection window state
  as current boundaries when discussing scalability.
