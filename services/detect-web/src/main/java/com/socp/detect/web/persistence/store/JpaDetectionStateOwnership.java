package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.DetectionStateOwnerEntity;
import com.socp.detect.web.persistence.repository.DetectionStateOwnerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL/H2-backed state-unit lease.  The update predicates are the
 * fencing boundary: a worker that lost the row's epoch cannot renew or
 * release the new owner's lease.
 */
@Component
public class JpaDetectionStateOwnership implements DetectionStateOwnership {

    private final DetectionStateOwnerRepository repository;
    private final String ownerId;
    private final Duration leaseDuration;
    private final ConcurrentHashMap<String, Object> unitLocks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public JpaDetectionStateOwnership(
            DetectionStateOwnerRepository repository,
            @Value("${socp.detect.instance-id:}") String configuredInstanceId,
            @Value("${socp.detect.state.owner-lease:30s}") String configuredLease) {
        this(repository, uniqueOwnerId(configuredInstanceId), parseDuration(configuredLease));
    }

    /** Focused-test constructor with deterministic lease settings. */
    public JpaDetectionStateOwnership(DetectionStateOwnerRepository repository,
                                      String ownerId, Duration leaseDuration) {
        this.repository = repository;
        this.ownerId = ownerId == null || ownerId.isBlank() ? uniqueOwnerId("") : ownerId;
        this.leaseDuration = normalizeDuration(leaseDuration);
    }

    @Override
    public Lease acquire(String inputTopic, int partition, int shard) {
        String key = DetectionStateOwnership.unitKey(inputTopic, partition, shard);
        Object lock = unitLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            for (int attempt = 0; attempt < 3; attempt++) {
                Instant now = Instant.now();
                Instant leaseUntil = now.plus(leaseDuration);
                int updated = repository.claimExisting(key, ownerId, now, leaseUntil);
                if (updated == 1) return readLease(key, inputTopic, partition, shard);

                try {
                    int inserted = repository.insert(key, inputTopic, partition, shard,
                            ownerId, 1L, leaseUntil, now);
                    if (inserted == 1) {
                        return new Lease(inputTopic, partition, shard, ownerId, 1L, leaseUntil);
                    }
                } catch (DataIntegrityViolationException concurrentClaim) {
                    // Another instance inserted the unit between the update
                    // and insert. Retry the compare-and-set update so an
                    // expired row can be taken over without blind overwrite.
                }
            }
        }
        throw new IllegalStateException("detection state unit is owned by another live instance: " + key);
    }

    @Override
    public void assertCurrent(Lease lease) {
        if (lease == null) throw new StaleStateOwnerException("detection state lease is missing");
        Instant now = Instant.now();
        int updated = repository.touch(lease.unitKey(), lease.ownerId(), lease.fencingEpoch(),
                now, now.plus(leaseDuration));
        if (updated != 1) {
            throw new StaleStateOwnerException("detection state lease was fenced: " + lease.unitKey()
                    + " epoch=" + lease.fencingEpoch());
        }
    }

    @Override
    public void release(Lease lease) {
        if (lease == null) return;
        repository.release(lease.unitKey(), lease.ownerId(), lease.fencingEpoch(), Instant.now());
    }

    String ownerId() {
        return ownerId;
    }

    Duration leaseDuration() {
        return leaseDuration;
    }

    private Lease readLease(String key, String inputTopic, int partition, int shard) {
        DetectionStateOwnerEntity row = repository.findById(key)
                .orElseThrow(() -> new IllegalStateException("state owner disappeared after claim: " + key));
        return new Lease(inputTopic, partition, shard, row.getOwnerId(),
                row.getFencingEpoch(), row.getLeaseUntil());
    }

    private static String uniqueOwnerId(String configured) {
        String base = configured == null ? "" : configured.trim();
        if (base.isBlank()) base = "detect";
        String suffix = UUID.randomUUID().toString();
        int maxBase = Math.max(1, 128 - suffix.length() - 1);
        if (base.length() > maxBase) base = base.substring(0, maxBase);
        return base + "-" + suffix;
    }

    private static Duration normalizeDuration(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) return Duration.ofSeconds(30);
        return value.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : value;
    }

    private static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) return Duration.ofSeconds(30);
        String normalized = value.trim().toLowerCase();
        try {
            if (normalized.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(normalized.substring(0, normalized.length() - 2)));
            }
            if (normalized.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            }
            if (normalized.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            }
            return Duration.ofMillis(Long.parseLong(normalized));
        } catch (NumberFormatException failure) {
            return Duration.ofSeconds(30);
        }
    }
}
