# Detection rules

Detection rules consume normalized events produced by the ingest parser. A
condition reads a canonical field (`source`, `host`, `severity`, `raw`) or any
normalized/custom field in `event.fields` such as `src_ip`, `user`, `action`,
or `http_method`.

Stateful rule grouping is partition-local. Use the same field for `groupBy`
and `routingField` (with `keyField` retained as a compatibility alias). The
validation endpoint reports cross-entity grouping as invalid, and create/update
requests return HTTP 400 until an explicit repartition/fan-out plan is
available.

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

<!-- content-pack:start -->
## Content pack `socp-core-detections` (v2026.09.13)

The pack ships 39 versioned detections. Every rule declares its
data sources, MITRE ATT&CK mapping, an investigation guide and known false
positives; every rule also carries positive and negative test vectors that are
executed against the real rule engine by
`DetectionContentExecutionTest` and contract-checked by
`DetectionContentCatalogTest`.

| Rule id | Name | Severity | ATT&CK | Data sources | Version |
| --- | --- | --- | --- | --- | --- |
| AUTH-BRUTE | SSH 暴力破解 | HIGH | T1110 | auth, sshd | 1.1.0 |
| AUTH-BRUTE-SUCCESS | 暴力破解得手（关联） | CRITICAL | T1110, T1078 | auth, sshd | 1.0.0 |
| AUTH-PRIVESC | 权限提升 | CRITICAL | T1548 | auth, auditd, linux | 1.0.0 |
| CRED-DUMP | 凭据转储 | CRITICAL | T1003 | edr, auditd, sysmon | 1.0.0 |
| AUTH-NEW-ADMIN | New privileged account | HIGH | T1098 | linux, auditd | 1.0.0 |
| AUTH-ROOT-LOGIN | Direct root login | HIGH | T1078 | auth, sshd | 1.0.0 |
| LATERAL-RDP | RDP lateral movement | HIGH | T1021.001 | firewall, windows | 1.0.0 |
| LATERAL-SMB | SMB lateral movement | HIGH | T1021.002 | firewall, windows, edr | 1.0.0 |
| EXEC-POWERSHELL | Suspicious PowerShell | HIGH | T1059.001 | sysmon, edr | 1.0.0 |
| EXEC-SHELL | Suspicious shell execution | HIGH | T1059 | auditd, edr, falco | 1.0.0 |
| PERSIST-CRON | Cron persistence | HIGH | T1053.003 | auditd, linux, edr | 1.0.0 |
| PERSIST-TASK | Scheduled task persistence | HIGH | T1053.005 | sysmon, windows, edr | 1.0.0 |
| EVADE-LOGCLEAR | Log clearing | CRITICAL | T1070.001 | windows, auditd, edr | 1.0.0 |
| IOC-BLOCKED-IP | Blocked IP activity | CRITICAL | T1071 | firewall, proxy, auth | 1.0.0 |
| WEB-SQLI | SQL injection | HIGH | T1190 | nginx, waf, web | 1.0.0 |
| WEB-TRAVERSAL | Path traversal | HIGH | T1006 | nginx, waf, web | 1.0.0 |
| C2-BEACON | C2 beacon | HIGH | T1071.001 | proxy, edr, dns | 1.0.0 |
| EXFIL-LARGE | Large data exfiltration | HIGH | T1041 | proxy, netflow, dlp | 1.0.0 |
| DOS-FLOOD | Network flood | HIGH | T1498 | firewall, netflow, waf | 1.0.0 |
| CORR-FAIL-SUDO | Failed login followed by sudo | CRITICAL | T1110, T1548 | auth, auditd, linux | 1.0.0 |
| RISK-ENTITY-SPIKE | Entity risk accumulation | CRITICAL | T1078 | alert, risk | 1.0.0 |
| RARE-PROCESS | Rare process on host | HIGH | T1059 | sysmon, auditd, edr | 1.0.0 |
| RARE-DOMAIN | Rare destination domain | MEDIUM | T1071.004 | dns, proxy, edr | 1.0.0 |
| BASELINE-AUTH-VOLUME | Authentication volume baseline deviation | HIGH | T1078 | auth, sshd, windows | 1.0.0 |
| CORR-ATTACK-SIGNALS | Multi-stage host attack signals | CRITICAL | T1078, T1548, T1059 | auth, auditd, edr | 1.0.0 |
| EXEC-SUSPICIOUS-SHELL | Suspicious shell execution | HIGH | T1059 | edr, sysmon, auditd | 1.0.0 |
| FW-SCAN | Firewall scan | MEDIUM | T1046 | firewall | 1.0.0 |
| MAL-C2 | Suspected C2 traffic | HIGH | T1071 | proxy | 1.0.0 |
| PHISH-MAIL | Phishing mail delivery | MEDIUM | T1566 | mail | 1.0.0 |
| RANSOM-ENCRYPT | Ransomware encryption | CRITICAL | T1486 | edr | 1.0.0 |
| UEBA-AUTH-SPIKE | Authentication volume spike | HIGH | T1110 | auth | 1.0.0 |
| UEBA-NEW-DEST | First-seen destination | MEDIUM | T1071 | proxy | 1.0.0 |
| UEBA-NEW-GEO | First-seen login geography | HIGH | T1078 | auth | 1.0.0 |
| UEBA-NEW-PROCESS | First-seen process | MEDIUM | T1059 | edr | 1.0.0 |
| UEBA-USER-VOLUME | Account activity spike | MEDIUM | T1078 | audit, application, auth | 1.0.0 |
| WATCH-BLOCKED-IP | Blocked IP activity | CRITICAL | T1071 | firewall, proxy, auth | 1.0.0 |
| WATCH-CROWN-JEWEL | Crown-jewel access | HIGH | T1021 | firewall, netflow, application | 1.0.0 |
| WATCH-PRIV-ACCOUNT | Privileged account operation | HIGH | T1078 | audit, database, linux | 1.0.0 |
| WEB-ATTACK | Web attack indicator | HIGH | T1190 | web, waf | 1.0.0 |

### Authoring a new rule

1. Append an entry to `rules[]` in
   `services/detect-web/src/main/resources/detection-content/manifest.json`
   with `id`, `version`, `status`, `owner`, `description`, `dataSources`,
   `mitre`, `references`, `investigationGuide`, `falsePositives`, the
   executable `spec` (the typed DSL documented in this file) and at least one
   positive and one negative vector in `tests`.
2. Bump the manifest `version`. The pack version lands in every persisted
   rule document through `DetectionContentCatalog.enrich` as `contentVersion`.
3. Run `mvnw -pl services/detect-web test`: the catalog contract test fails
   on missing metadata and the execution test fails on vectors that do not
   reproduce the expected alert behaviour.

<!-- content-pack:end -->
