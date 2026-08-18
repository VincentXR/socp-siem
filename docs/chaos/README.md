# Failure matrix

`build/chaos-pipeline.py` checks the failure semantics that are currently
implemented by the event path. It is intended for a running local or CI stack
and uses unique event IDs per invocation.

```bash
python build/chaos-pipeline.py --scenario all --count 20 \
  --output .cache/chaos/$(date +%Y%m%d-%H%M%S).json
```

The current matrix contains:

- `detect_restart`: stop `detect-web`, ingest while it is down, verify Kafka
  retains the backlog, restart the service, and wait for lag to return to zero;
- `duplicate_delivery`: submit the same canonical event twice and verify that
  the stable source alert identity results in one Alert Web row.

The script reports `pass` only when every selected scenario satisfies its
invariant. It does not silently convert a failed dependency into a pass.

Additional scenarios should follow the same structure: define the injected
failure, record a before/after snapshot, and state an observable invariant.
Avoid treating a service restart alone as a chaos test without checking event
loss, duplicate creation, lag recovery, or downstream replay behavior.
