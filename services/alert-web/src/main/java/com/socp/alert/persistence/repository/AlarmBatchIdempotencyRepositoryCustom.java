package com.socp.alert.persistence.repository;

import com.socp.alert.persistence.entity.AlarmBatchIdempotency;

public interface AlarmBatchIdempotencyRepositoryCustom {
    /** Reserve the response row in the same transaction as all alarm side effects. */
    boolean claim(AlarmBatchIdempotency row);
}
