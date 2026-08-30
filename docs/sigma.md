# Sigma import contract

SOCP exposes `POST /detect-web/api/v1/rules/import/sigma` for a deliberately
small, semantics-preserving Sigma subset. The endpoint accepts YAML and stores
the original source in the rule specification so an operator can review the
conversion before promotion.

Supported today:

- scalar selection fields;
- `and`, `or`, `1 of` and `all of` selection conditions (including `them` and
  prefix wildcards);
- `contains`, `startswith`, `endswith` and regular-expression modifiers;
- `level`, `status`, `timeframe`, `tags`/ATT&CK and common `logsource` metadata.

Unsupported syntax is rejected with a 4xx response rather than approximated:

- `not`, aggregation/count/near/before/after expressions;
- list-valued predicates;
- arithmetic and compound boolean expressions that cannot be represented by
  the current RuleSpec (`(A or B) and (C or D)`).

Imports are always persisted as `TESTING` with `enabled=false`, even when the
source says `status: stable`. This is an intentional lifecycle boundary:

```text
import -> TESTING -> test vectors/review -> POST /rules/{id}/activate -> ACTIVE
```

Only an administrator with `rule:activate` can perform the final transition.
Editing or creating a rule cannot directly activate it. The persisted JSON
retains `sigmaSource`, `sigmaVersion`, `sigmaStatus`, `sigmaLogsource`,
`contentPack`, `contentVersion`, and the normalized RuleSpec fields. Detection
execution uses only the normalized fields; the source metadata is evidence and
is not interpreted as executable instructions.

Every imported rule should have positive, negative, and boundary vectors before
promotion. The unit tests cover the conversion and explicit rejection paths;
the external content acceptance set is intentionally a separate, versioned
fixture pack so third-party rule updates cannot silently change production
behavior.
