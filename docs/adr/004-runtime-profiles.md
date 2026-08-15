# ADR 004: Explicit Runtime Profiles

- Status: accepted
- Date: 2026-08-15

## Decision

Support three operational modes:

- `local`: H2/file-backed services and safe development fallbacks.
- `integration`: Docker middleware and the real event/search/reporting path.
- `prod`: environment-provided credentials and `ProdGuard` fail-fast checks.

Temporal is optional locally but required by the `prod` guard. Authentication
may use HMAC for a single-node environment or JWKS/OIDC integration for a
deployed identity provider.

## Why

The complete stack is valuable for reproducible verification but expensive on
a developer laptop. Explicit profiles make the trade-off visible and prevent
development fallbacks from silently reaching production.
