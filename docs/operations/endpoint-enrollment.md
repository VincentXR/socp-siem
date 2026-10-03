# Endpoint enrollment and first-event verification

Endpoint Agents is a tenant-scoped registry and runtime evidence workspace, not
an installer or a containment console. SOCP does not currently ship a standalone
HIPS installation package. Use the supported Falco/Vector integration or the
Sysmon pipeline in `agents/`.

1. An authorized analyst/admin registers the endpoint using
   `POST /hips-web/api/v1/endpoints` with hostname, IP, OS and agentVersion.
   Use the authenticated gateway; do not expose a service directly.
2. Configure the collector with its tenant-bound registered credential and the
   documented collection endpoint. Keep tokens in the collector's secret
   configuration, never URL parameters. See [Vector pipeline](../../agents/vector-pipeline/README.md)
   and [Sysmon integration](../../agents/sysmon/README.md).
3. Start the collector and verify its first accepted event. Registry creation
   alone does not prove collection is running. Heartbeats expire according to
   the HIPS runtime policy; the ONLINE/OFFLINE filter applies the same expiry
   as the statistics.
4. Open the exact endpoint, inspect runtime history, then use **View matching
   events** to search by the stable eventId. An empty result is not successful
   delivery; inspect [forwarding receipts and recovery](endpoint-forwarding.md).
5. From a related asset, select an endpoint name to open that exact registration.
   Association uses exact non-empty IP or hostname, not free-text similarity.

Unregister removes the registry entry; it is a management action behind More,
requires an explicit confirmation, and is not evidence of host isolation.
