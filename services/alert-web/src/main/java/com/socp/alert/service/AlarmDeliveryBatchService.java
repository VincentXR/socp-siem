package com.socp.alert.service;

import com.socp.alert.domain.AlarmDelivery;
import com.socp.alert.persistence.repository.AlarmDeliveryRepository;
import com.socp.platform.data.outbox.OutboxRetryPolicy;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Bounded durable claim/receipt transactions; downstream I/O is performed by the publisher. */
@Service
public class AlarmDeliveryBatchService {
    private final AlarmDeliveryRepository repository;

    public AlarmDeliveryBatchService(AlarmDeliveryRepository repository) {
        this.repository = repository;
    }

    public record Claim(AlarmDelivery delivery, String token) { }
    public record Outcome(Claim claim, boolean delivered, boolean retryable, String error) { }
    public record Receipt(int delivered, int retry, int dead, int stale) { }

    @Transactional
    public List<Claim> claim(List<AlarmDelivery> candidates, int maxAttempts) {
        String tenant = validate(candidates);
        if (maxAttempts < 1) throw new IllegalArgumentException("max attempts must be positive");
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        var claimed = new ArrayList<Claim>();
        for (var row : candidates.stream().sorted(Comparator.comparing(AlarmDelivery::getId)).toList()) {
            int changed = repository.claimForTenant(tenant, row.getId(), now, maxAttempts, row.getAttempts(), token);
            if (changed == 1) claimed.add(new Claim(row, token));
            else if (changed != 0) throw new IllegalStateException("ambiguous alarm delivery claim");
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public Receipt acknowledge(List<Outcome> outcomes, int maxAttempts) {
        if (outcomes == null) throw new IllegalArgumentException("outcomes are required");
        String tenant = validate(outcomes.stream().map(outcome -> outcome.claim().delivery()).toList());
        if (maxAttempts < 1) throw new IllegalArgumentException("max attempts must be positive");
        for (var outcome : outcomes) {
            String token = outcome.claim().token();
            if (token == null || token.isBlank() || token.length() > 36) {
                throw new IllegalArgumentException("claim token is required");
            }
        }
        int delivered = 0, retry = 0, dead = 0, stale = 0;
        Instant now = Instant.now();
        for (var outcome : outcomes.stream().sorted(Comparator.comparing(item -> item.claim().delivery().getId())).toList()) {
            var claim = outcome.claim();
            var row = claim.delivery();
            int changed;
            if (outcome.delivered()) {
                changed = repository.markDeliveredForTenant(tenant, row.getId(), now, claim.token());
                delivered += changed;
            } else {
                var decision = OutboxRetryPolicy.afterClaim(row.getAttempts() + 1, maxAttempts, now, outcome.error(), 900);
                if (!outcome.retryable() || decision.exhausted()) {
                    changed = repository.markDeadForTenant(tenant, row.getId(),
                            (outcome.retryable() ? decision.error() : OutboxRetryPolicy.truncate(outcome.error(), 1024)), now, claim.token());
                    dead += changed;
                } else {
                    changed = repository.scheduleRetryForTenant(tenant, row.getId(), decision.nextAttemptAt(),
                            decision.error(), now, claim.token());
                    retry += changed;
                }
            }
            if (changed == 0) stale++;
            else if (changed != 1) throw new IllegalStateException("ambiguous alarm delivery receipt");
        }
        return new Receipt(delivered, retry, dead, stale);
    }

    private static String validate(List<AlarmDelivery> rows) {
        String tenant = TenantContext.require();
        if (rows == null || rows.size() > 32) throw new IllegalArgumentException("delivery batch limit is 32");
        var ids = new HashSet<String>();
        for (var row : rows) {
            if (row == null || !tenant.equals(row.getTenantId()) || row.getId() == null
                    || !ids.add(row.getId())) throw new IllegalArgumentException("delivery batch tenant or identity mismatch");
        }
        return tenant;
    }
}
