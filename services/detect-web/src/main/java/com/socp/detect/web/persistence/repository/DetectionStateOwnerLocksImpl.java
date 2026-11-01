package com.socp.detect.web.persistence.repository;

import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Fresh scalar reads avoid first-level entity-cache reuse at the fencing boundary. */
public class DetectionStateOwnerLocksImpl implements DetectionStateOwnerLocks {
    private final EntityManager entityManager;

    public DetectionStateOwnerLocksImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    @SuppressWarnings("unchecked")
    public List<DetectionStateOwnerRepository.LockedFence> lockFences(Collection<String> ownerKeys) {
        if (ownerKeys == null || ownerKeys.size() > 64) throw new IllegalArgumentException("owner lock batch limit is 64");
        if (ownerKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("owner lock keys are required");
        }
        List<String> keys = ownerKeys.stream().distinct().sorted().toList();
        if (keys.isEmpty()) return List.of();
        String parameters = IntStream.rangeClosed(1, keys.size()).mapToObj(index -> "?" + index)
                .collect(Collectors.joining(","));
        StringBuilder sql = new StringBuilder("select owner_key, owner_id, fencing_epoch from t_detection_state_owner ")
                .append("where owner_key in (").append(parameters).append(") order by case owner_key ");
        // Database collations can order topic punctuation differently from Java.
        // Explicit ordinals preserve the lock order used by every scalar guard
        // and checkpoint. Only bounded ordinals and placeholders enter SQL text.
        for (int index = 0; index < keys.size(); index++) {
            sql.append("when ?").append(index + 1).append(" then ").append(index).append(' ');
        }
        sql.append("end for update");
        var query = entityManager.createNativeQuery(sql.toString());
        for (int index = 0; index < keys.size(); index++) query.setParameter(index + 1, keys.get(index));
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> (DetectionStateOwnerRepository.LockedFence)
                new FenceRow((String) row[0], (String) row[1], ((Number) row[2]).longValue())).toList();
    }

    private record FenceRow(String ownerKey, String ownerId, long fencingEpoch)
            implements DetectionStateOwnerRepository.LockedFence {
        @Override public String getOwnerKey() { return ownerKey; }
        @Override public String getOwnerId() { return ownerId; }
        @Override public long getFencingEpoch() { return fencingEpoch; }
    }
}
