# Detection rules

Detection rules consume normalized events produced by the ingest parser. A
condition reads a canonical field (`source`, `host`, `severity`, `raw`) or any
normalized/custom field in `event.fields` such as `src_ip`, `user`, `action`,
or `http_method`.

## Alert templates

`message` remains supported for compatibility. New rules should use an
`alert` object so the alert title and content are independently configurable:

```json
{
  "id": "AUTH-BRUTE-CUSTOM",
  "name": "SSH brute force",
  "type": "threshold",
  "severity": "HIGH",
  "keyField": "src_ip",
  "threshold": 5,
  "window": "60s",
  "alert": {
    "title": "Repeated login failures from {{event.src_ip}}",
    "description": "{{count}} failures for {{key}} within {{window}} on {{event.host}}"
  },
  "match": [
    {"field": "source", "op": "eq", "value": "auth"},
    {"field": "msg", "op": "contains", "value": "Failed password"}
  ]
}
```

Templates are deliberately logic-free. They support event values with
`{{event.field}}` (or `{{fields.field}}`) and rule evaluation values such as
`{{key}}`, `{{count}}`, and `{{window}}`. The legacy `{host}` and `{count}`
forms continue to work.

## Rule whitelist

`whitelist` is a rule-level exclusion list. Conditions in this array use the
same DSL as `match`; if any whitelist condition matches, the event is excluded
from that rule before it can trigger an alert. This is OR semantics across
whitelist rows, while each row is one condition.

Use `inlist` when the value is the name of a tenant-scoped dynamic watchlist:

```json
"whitelist": [
  {"field": "src_ip", "op": "inlist", "value": "trusted_ips"},
  {"field": "user", "op": "inlist", "value": "service_accounts"}
]
```

The existing watchlist API manages those values at
`/detect-web/api/v1/watchlists`. Literal exclusions can use operators such as
`eq`, `contains`, or `regex`. A whitelist does not disable the rule globally;
it only suppresses events matching its configured exclusions.
