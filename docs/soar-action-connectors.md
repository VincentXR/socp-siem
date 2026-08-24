# SOAR real action connector contract

SOAR does not treat an HTTP `2xx` response alone as proof that a containment or
forensics action happened. The configured adapter must return JSON containing
one of:

- `{"accepted": true, "operationId": "..."}`
- `{"verified": true, "operationId": "..."}`
- `{"status": "accepted|executed|already_applied|success", "operationId": "..."}`

The request body contains the alarm context, `soarAction`, and a stable
`idempotencyKey`. Adapters must use that key to deduplicate retries and return
the same operation receipt for a duplicate request.

Configure the adapters with:

```text
SOCP_SOAR_FIREWALL_BLOCK_URL
SOCP_SOAR_NETWORK_ISOLATION_URL
SOCP_SOAR_SNAPSHOT_URL
SOCP_SOAR_CONNECTOR_TIMEOUT_MS=5000
```

An empty endpoint is a failed action (`CONNECTOR_NOT_CONFIGURED`). Local dry-run
actions remain explicit `simulate:<action>` entries and return `simulated`; the
production profile disables them.
