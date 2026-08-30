# Sysmon Golden Path

This directory contains a minimal Sysmon configuration and a redacted Event ID
1 sample. The sample is intentionally synthetic (`demo-not-real`) and must not
be presented as telemetry from a real host.

Install Sysmon on a disposable Windows host, apply the configuration, export
JSON through Winlogbeat/Vector, then send it to the authenticated
`search-config` ingest endpoint. The parser maps Process Create, Network
Connect and File Create fields into the canonical ECS-style keys before the
normal Detection/Alert/Incident pipeline.

```powershell
sysmon64.exe -accepteula -i sysmonconfig-export.xml
```

For a deterministic CI/demo replay of the redacted fixture:

```bash
python build/demos/sysmon-golden-path.py --sample agents/sysmon/sample-process-create.json
```

The replay script requires a registered collector credential and only asserts
the ingest acknowledgement. A full alert assertion should use a rule pack that
explicitly enables a PowerShell Process Create rule; no real endpoint action is
performed by this fixture.
