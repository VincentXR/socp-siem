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
    private final Object transactionFenceKey = new Object();
    private final String ownerId;
    private final Duration leaseDuration;
    private final ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> unitLocks = new ConcurrentHashMap<>();

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
        java.util.concurrent.locks.ReentrantLock lock = unitLocks.computeIfAbsent(key, ignored -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                Instant now = Instant.now();
                Instant leaseUntil = now.plus(leaseDuration);
                invalidateTransactionFence(key);
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
        } finally {
            lock.unlock();
        }
        throw new IllegalStateException("detection state unit is owned by another live instance: " + key);
    }

    @Override
    public void assertCurrent(Lease lease) {
        if (lease == null) throw new StaleStateOwnerException("detection state lease is missing");
        var checked = transactionFences();
        FenceIdentity identity = new FenceIdentity(lease.unitKey(), lease.ownerId(), lease.fencingEpoch());
        if (checked != null && checked.contains(identity)) return;
        Instant now = Instant.now();
        int updated = repository.touch(lease.unitKey(), lease.ownerId(), lease.fencingEpoch(),
                now, now.plus(leaseDuration));
        if (updated != 1) {
            throw new StaleStateOwnerException("detection state lease was fenced: " + lease.unitKey()
                    + " epoch=" + lease.fencingEpoch());
        }
        if (checked != null) checked.add(identity);
    }

    private record FenceIdentity(String unitKey, String ownerId, long epoch) { }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void assertCurrentBatch(java.util.List<Lease> leases) {
        if (leases == null || leases.size() > 64) throw new IllegalArgumentException("ownership batch limit is 64");
        var requested = new java.util.TreeMap<String, Lease>();
        for (Lease lease : leases) {
            if (lease == null) throw new StaleStateOwnerException("detection state lease is missing");
            Lease previous = requested.putIfAbsent(lease.unitKey(), lease);
            if (previous != null && (!previous.ownerId().equals(lease.ownerId())
                    || previous.fencingEpoch() != lease.fencingEpoch())) {
                throw new StaleStateOwnerException("conflicting detection state leases: " + lease.unitKey());
            }
        }
        var checked = transactionFences();
        if (checked != null) requested.values().removeIf(lease -> checked.contains(
                new FenceIdentity(lease.unitKey(), lease.ownerId(), lease.fencingEpoch())));
        // A scalar UPDATE is cheaper for one or two owners. Standalone callers
        // without a transaction cannot retain SELECT FOR UPDATE row locks.
        if (requested.size() < 3 || checked == null) {
            requested.values().forEach(this::assertCurrent);
            return;
        }
        var rows = repository.lockFences(requested.keySet());
        if (rows.size() != requested.size()) throw new StaleStateOwnerException("detection state owner is missing");
        var validated = new java.util.HashSet<String>();
        for (var row : rows) {
            Lease lease = requested.get(row.getOwnerKey());
            if (lease == null || !validated.add(row.getOwnerKey())
                    || !lease.ownerId().equals(row.getOwnerId()) || lease.fencingEpoch() != row.getFencingEpoch()) {
                throw new StaleStateOwnerException("detection state lease was fenced: " + row.getOwnerKey());
            }
        }
        // Exclusive owner-row locks survive through the outer outbox/journal
        // commit. No other replica can change these tokens between validation
        // and renewal, or before the caller's durable writes are acknowledged.
        Instant now = Instant.now();
        if (repository.renewLockedFences(requested.keySet(), now, now.plus(leaseDuration)) != requested.size()) {
            throw new StaleStateOwnerException("detection state owner disappeared during renewal");
        }
        requested.values().forEach(lease -> checked.add(
                new FenceIdentity(lease.unitKey(), lease.ownerId(), lease.fencingEpoch())));
    }

    /**
     * The first UPDATE or batch lock holds the owner row until this exact transaction ends.
     * Rechecking the same epoch inside it cannot observe a new owner. Never
     * reuse this fact outside that transaction or across REQUIRES_NEW scopes.
     */
    @SuppressWarnings("unchecked")
    private java.util.Set<FenceIdentity> transactionFences() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        var existing = (java.util.Set<FenceIdentity>) org.springframework.transaction.support.TransactionSynchronizationManager
                .getResource(transactionFenceKey);
        if (existing != null) return existing;
        java.util.Set<FenceIdentity> checked = new java.util.HashSet<>();
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(transactionFenceKey, checked);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void suspend() {
                        org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(transactionFenceKey);
                    }
                    @Override public void resume() {
                        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(transactionFenceKey, checked);
                    }
                    @Override public void savepointRollback(Object savepoint) {
                        checked.clear();
                    }
                    @Override public void afterCompletion(int status) {
                        org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(transactionFenceKey);
                    }
                });
        return checked;
    }

    @SuppressWarnings("unchecked")
    private void invalidateTransactionFence(String unitKey) {
        var checked = (java.util.Set<FenceIdentity>) org.springframework.transaction.support.TransactionSynchronizationManager
                .getResource(transactionFenceKey);
        if (checked != null) checked.removeIf(identity -> identity.unitKey().equals(unitKey));
    }

    @Override
    public void release(Lease lease) {
        if (lease == null) return;
        invalidateTransactionFence(lease.unitKey());
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
