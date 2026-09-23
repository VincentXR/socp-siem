# Notification delivery

Notify accepts service-authenticated alarms at
`POST /notify-web/api/v1/notify/alert`. The owning service takes the tenant from
authenticated context. Alert's durable delivery outbox remains the retry owner;
Notify does not acknowledge unfinished channels as delivered.

## Receipts and concurrent requests

The identity remains `(tenant_id, alarm_id, channel_id)`, with the existing
deterministic receipt ID. V5 admits a row before invoking a connector. A short
database transaction claims a unique token for 120 seconds using database time.
Competing requests return `failed / NOTIFY_PENDING`, preserving the existing
HTTP 502 and nonzero response-envelope contract for partial failure. A completed
receipt returns its cached result with `duplicate: true`.

Connector I/O runs outside the database transaction. Completion checks tenant,
receipt ID and claim token before recording its result. A callback from a
reclaimed attempt cannot replace the current attempt or its completed receipt.
Failed attempts have no `delivered_at` and become eligible after five seconds.
An abandoned claim becomes eligible after its 120-second lease. There is no
independent background resend in Notify: a subsequent Alert outbox request
performs the retry. Alert's retry budget and explicit DEAD/requeue workflow still
apply.

Remote acceptance followed by a lost response, process termination or database
failure can still cause a duplicate notification on retry. The token fences
local receipt writes; it does not undo or deduplicate an external side effect.
SMTP acceptance is not proof that a recipient read or received the message.
Operators should inspect destination records before manually replaying an
uncertain delivery. Do not delete receipts or turn unconfirmed rows into success.

## Capacity and configuration

- A tenant can configure 64 channels and enable 16. Database tenant locks
  serialize create, edit, toggle and delete across replicas. Existing excess
  configuration is retained for cleanup: listing is paged, disabling/deletion
  remain available, and dispatch refuses more than 16 enabled channels rather
  than dropping the remainder. Quota conflicts return HTTP 409.
- `GET /channels` keeps its page envelope and now pages in the database, ordered
  by name then ID. Page size is 1–500 by default, with the existing `size=0`
  count-only response retained; invalid/overflowing offsets return 400. Channel
  targets accept the API's existing 2,048-character limit.
- Each Notify instance admits at most 32 connector tasks with no waiting queue.
  Saturation returns `NOTIFY_BUSY`. A dispatch waits at most five seconds for
  admitted work after channel lookup; unfinished results remain `NOTIFY_PENDING`.
  Such work may continue and persist a receipt after the HTTP response. Capacity
  is released only when actual work finishes, not when the caller stops waiting.
  Selected-channel tests use the same capacity and response-wait bounds.
  Tests have distinct identities and no reusable alarm receipt. An interrupted
  or timed-out test reports `NOTIFY_TEST_UNCONFIRMED`; inspect recent dispatch
  history or the destination before another test, which can send another message.
- Alarm payloads are limited to 256 KiB of serialized JSON (HTTP 413 above it). Each HTTP connector
  invocation has a three-second request timeout and one transport attempt,
  regardless of `socp.client.max-attempts`. Existing external endpoint policy,
  credential isolation and response-body limits continue to apply.
- SMTP and SMTPS default connection, read and write timeouts to 3,000 ms each.
  An enabled connector requires `JavaMailSenderImpl` with all three values in
  1–10,000 ms; unsupported senders or unbounded overrides fail before sending.
  These are socket-operation timeouts, not a total SMTP transaction deadline.
  See the [Spring Boot mail configuration](https://docs.spring.io/spring-boot/3.5/reference/io/email.html)
  and [Angus SMTP properties](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html).

`NOTIFY_RECEIPT_UNCONFIRMED` means a connector result could not be durably
confirmed; `NOTIFY_CLAIM_LOST` means a newer attempt owns the row. Neither is a
successful acknowledgement. Dispatch logs are a best-effort recent history;
`t_notification_delivery` is the durable acknowledgement boundary.

## V5 upgrade

Stop admission to old Notify instances and drain/stop all of them before V5
workers start. V5 retains existing completed receipts, permits null
`delivered_at` for pending attempts, adds token/retry timestamps and the channel
namespace lock table, and expands `t_channel.target`. Old dispatchers interpret
any row as a successful receipt and must not run alongside V5 pending rows.
Apply the tenant-RLS policy to the new namespace table using the normal
migration-role/runtime-role deployment procedure. An application rollback also
requires draining V5 work and resolving pending rows; do not simply start the
old dispatcher against pending receipts.

Local evidence is provided by `NotificationPersistenceTest` and its PostgreSQL
subclass, `NotificationPostgresTest`: concurrent admission, stale callbacks,
rollback, tenant isolation, quota races, real dispatcher callbacks and a populated
V4→V5 upgrade. Run `SOCP_TESTCONTAINERS=true bash build/mvnw.sh -pl
services/notify-web -am test -Dtest=NotificationPostgresTest
-Dsurefire.failIfNoSpecifiedTests=false` against disposable local Docker.
These tests do not establish production throughput or destination-side deduplication.
