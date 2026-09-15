# Distributed tracing

The event path crosses four process boundaries: the gateway, a business
service, Kafka, and a consumer on another thread. This page describes how one
trace stays one trace across all of them, and where the joins are tested.

## Why a trace id in the MDC is not a trace

Putting `traceId` into the MDC makes both sides of a hop print the same string.
That is log correlation, and it is worth keeping. It is not tracing: no
exporter can join two spans that merely share an identifier, so the hop does not
appear in the trace tree and the time spent in it is invisible.

Tracing requires a `Context` to cross the boundary and become the **parent** of
the next span. The W3C `traceparent` header is the wire format;
`io.opentelemetry.context.Context` is the in-process form. Copying the header
string without building a context, or building a context with an invented
span-id, produces a parent that no exporter has ever seen — the same trace id,
but a dangling parent and no tree.

## Hops

| Hop | Carrier | Parent source |
| --- | --- | --- |
| Client → gateway | HTTP `traceparent` | extracted from the inbound request |
| Gateway → service | HTTP `traceparent` | the gateway's own SERVER span |
| Service → Kafka | record header `traceparent` | the current span, or a persisted value |
| Kafka → consumer | record header `traceparent` | extracted from the record |

Two shared helpers implement this:

- `platform/socp-obs` — `TracePropagation`, transport-agnostic: extract, inject,
  start a span under a parent, and render a context as a `traceparent` for
  storage.
- `platform/socp-client` — `KafkaTrace`, the `Headers` adapters plus the
  consumer span lifecycle (`runConsumed`, `callConsumed`).

### The gateway

`GatewayFilter` extracts the caller's context, opens a SERVER span, injects that
span into every forwarded request, and ends the span on the reactive signal via
`doFinally` — WebFlux has no request-scoped thread, so a `try/finally` would
close the span on the wrong thread or not at all. An inbound `traceparent` can
never choose the parent of the gateway's own span: the injection overwrites the
header rather than appending to it.

### Kafka

Producers open a PRODUCER span and inject it. Consumers extract the header into
a Context and open a CONSUMER span **parented to it**, which is what makes
`KafkaEventConsumer`, `OsIndexerConsumer`, and `AlarmEventConsumer` children of
the producing span rather than roots.

A transactional outbox breaks this: the row is drained later, on a scheduler
thread that inherits neither the MDC nor the OTel context. Both outbox paths
therefore persist the `traceparent` on the row at write time, while a span is
still current, and rejoin it at publish time:

- `IngestionOutboxEvent.traceparent`, captured in `IngestionCommitService`
- `DetectionAlertOutboxEntity.traceparent`, captured in
  `DetectionAlertOutboxService.enqueue` (added in `V20`)

Publishing then opens a PRODUCER span under the stored context, so the hop has
its own place in the tree instead of being elided.

## Enabling export

Propagation alone produces no data to look at. Export is opt-in and unchanged in
its defaults:

```bash
SOCP_TRACING_ENABLED=true
SOCP_OTLP_ENDPOINT=http://localhost:4317   # default; an OTel collector or Jaeger
```

With export off the OTel SDK is not installed and the legacy `X-Trace-Id` path
keeps log correlation working, which is why the previous behaviour had to be
preserved rather than replaced.

## What is tested

`KafkaTraceTest` asserts on **exported span data**, not on MDC contents, because
exported data is what a collector actually receives. It covers:

- the consumer span's `parentSpanId` equals the producer span's `spanId`
- the record header names the producer span, not just the trace
- a stored `traceparent` rejoins the original trace after its span has ended
- a record with no header roots a trace of its own instead of inheriting junk
