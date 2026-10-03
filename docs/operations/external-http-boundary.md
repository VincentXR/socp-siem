# External HTTP transport boundary

User-configured SOAR/Notify calls through `SocpHttpClient`, LLM chat completions,
and TAXII collection pages use `PinnedHttpTransport`. `ExternalEndpointPolicy`
validates scheme, host allowlist and resolved addresses; the resulting immutable
address snapshot is passed to the connection manager used for the actual socket.
The original URL hostname remains the TLS/SNI and certificate-verification name.
Redirects and implicit transport retries are disabled. A thread-local DNS scope
alone does not secure JDK `HttpClient`, whose connection work may run elsewhere.

The platform uses Spring Boot's managed Apache `httpclient5` dependency for its
request-scoped DNS resolver and cancellable connection handling. Internal service
calls retain the existing signed JDK-client path and its existing input contracts.
No production certificate-verification bypass is provided.

## Bounds, cancellation and retries

- External `SocpHttpClient` request bodies default to 1 MiB via
  `socp.client.request-body-limit-bytes`, with a transport ceiling of 16 MiB
- That new request cap applies only to external egress. Internal ingest batches
  keep their owning service's existing limits and are not silently truncated
- LLM serialized requests are limited to 1 MiB; TAXII uses GET requests
- Decoded response bodies, including gzip decompression, obey the finite
  `socp.client.response-body-limit-bytes` value and a 16 MiB transport ceiling
  LLM and TAXII retain the shared default response limit
- Each call has a total deadline covering connection and streamed body reads
  Caller interruption cancels the HTTP exchange; it does not leave a background
  request continuing after the caller gives up
- SOAR action execution uses exactly one transport attempt. Playbook/workflow
  retry and idempotency policy remains responsible for admitting another attempt
  Generic external callers retain their explicitly configured retry policy
- TAXII retains its same-host/same-port pagination checks and 100-page ceiling

Tenant connection secret references additionally require the exact operator-owned
[tenant grant](soar-secret-grants.md). An endpoint allowlist or possession of a
provider bootstrap credential never grants secret access to a tenant.

Local regressions exercise otherwise-unresolvable hostnames against pinned local
servers, valid and invalid TLS hostname checks, decoded gzip limits, streaming
deadlines, cancellation, one-attempt SOAR dispatch and internal bulk compatibility.
These are local boundary tests, not proof of production provider reachability or
middleware availability.

## Transport dependency security

The parent POM directly manages `httpcore5` and `httpcore5-h2` at 5.4.3,
retaining HttpClient 5.5.2. Core 5.4.3 is the first stable fix for
[HTTP/1 header exhaustion](https://github.com/advisories/GHSA-hf6x-8p5f-cgmf)
and [HTTP/2 header-list limits](https://github.com/advisories/GHSA-v3jc-474w-2wm6).
The imported Spring Boot BOM alone resolves an older vulnerable Core line;
changing a property without direct dependency management is insufficient.
`HttpCoreDependencyContractTest` verifies the actually loaded JAR versions,
requires aligned stable patched Core/H2 artifacts, and rejects a downgrade.
