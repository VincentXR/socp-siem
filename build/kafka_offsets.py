"""Read-only Kafka offset accounting shared by operational probes."""


def offset_snapshot(ends, committed):
    """Sum partition lag without treating OffsetFetch's unset (-1) as backlog.

    Missing commits start at zero, so a nonempty uncommitted partition still
    contributes its full backlog. Sum lag per partition: an offset beyond one
    partition's end must never mask pending records in a different partition.
    """
    per_partition = []
    for tp, end in sorted(ends.items(), key=lambda item: item[0].partition):
        value = committed.get(tp)
        current = value if isinstance(value, int) else (
            value.offset if value is not None else 0)
        if current < -1 or end < 0:
            raise ValueError(f"Invalid Kafka offsets for {tp}: {current}, {end}")
        current = max(0, current)
        per_partition.append({"partition": tp.partition, "end": end,
                              "committed": current, "lag": max(0, end - current)})
    return {"end": sum(item["end"] for item in per_partition),
            "committed": sum(item["committed"] for item in per_partition),
            "lag": sum(item["lag"] for item in per_partition),
            "partitions": len(per_partition),
            "perPartition": per_partition,
            "source": "kafka-python"}
