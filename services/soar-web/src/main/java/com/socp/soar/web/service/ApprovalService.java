package com.socp.soar.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.persistence.entity.ApprovalEntity;
import com.socp.soar.web.persistence.repository.ApprovalRepository;
import com.socp.soar.web.persistence.store.PlaybookStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Approval state machine for destructive/high-impact SOAR actions. */
@Service
public class ApprovalService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private final ApprovalRepository repository;
    private final PlaybookStore playbookStore;
    private final PlaybookExecutor executor;

    public ApprovalService(ApprovalRepository repository, PlaybookStore playbookStore,
                           PlaybookExecutor executor) {
        this.repository = repository;
        this.playbookStore = playbookStore;
        this.executor = executor;
    }

    public boolean requiresApproval(String playbookId) {
        Playbook playbook = playbookStore.get(playbookId);
        return ApprovalPolicy.requiresApproval(playbook);
    }

    public Map<String, Object> request(String playbookId, Map<String, Object> scope,
                                       String requestedBy, String reason) {
        if (!requiresApproval(playbookId)) throw new IllegalArgumentException("playbook has no high-risk action");
        String tenant = TenantContext.require();
        Instant now = Instant.now();
        ApprovalEntity entity = new ApprovalEntity();
        entity.setApprovalId("APR-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
        entity.setTenantId(tenant);
        entity.setPlaybookId(playbookId);
        entity.setRequestedBy(normalize(requestedBy, "unknown"));
        entity.setReason(normalize(reason, "high-risk SOAR action"));
        entity.setScopeJson(write(scope == null ? Map.of() : scope));
        entity.setStatus("PENDING");
        entity.setExpiresAt(now.plus(DEFAULT_TTL));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return view(repository.save(entity));
    }

    public Map<String, Object> approve(String approvalId, String approver, String reason) {
        ApprovalEntity entity = find(approvalId);
        if (!"PENDING".equals(entity.getStatus())) throw invalid("approval is not pending");
        if (expired(entity)) {
            entity.setStatus("EXPIRED");
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            throw invalid("approval has expired");
        }
        entity.setStatus("APPROVED");
        entity.setApprovedBy(normalize(approver, "unknown"));
        if (reason != null && !reason.isBlank()) entity.setReason(reason.trim());
        entity.setUpdatedAt(Instant.now());
        return view(repository.save(entity));
    }

    public Map<String, Object> execute(String approvalId) {
        ApprovalEntity entity = find(approvalId);
        if (!"APPROVED".equals(entity.getStatus())) throw invalid("approval is not approved");
        if (expired(entity)) {
            entity.setStatus("EXPIRED");
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            throw invalid("approval has expired");
        }
        Map<String, Object> scope = new LinkedHashMap<>(read(entity.getScopeJson()));
        scope.put("approvalId", entity.getApprovalId());
        // The approval ID is the stable command identity when the caller did
        // not provide a domain alarm ID. This keeps retries idempotent.
        scope.putIfAbsent("id", "approval-" + entity.getApprovalId());
        Map<String, Object> result = executor.runApprovedById(entity.getPlaybookId(), scope);
        boolean successful = !"FAILED".equalsIgnoreCase(String.valueOf(result.get("status")))
                && !"APPROVAL_REQUIRED".equalsIgnoreCase(String.valueOf(result.get("status")));
        entity.setStatus(successful ? "EXECUTED" : "EXECUTION_FAILED");
        entity.setExecutionId(String.valueOf(result.getOrDefault("executionId", "")));
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return result;
    }

    public List<Map<String, Object>> list() {
        return repository.findTop200ByTenantIdOrderByCreatedAtDesc(TenantContext.require()).stream()
                .map(ApprovalService::view).toList();
    }

    private ApprovalEntity find(String approvalId) {
        return repository.findByApprovalIdAndTenantId(approvalId, TenantContext.require())
                .orElseThrow(() -> invalid("approval not found"));
    }

    private static boolean expired(ApprovalEntity entity) {
        return entity.getExpiresAt() == null || !entity.getExpiresAt().isAfter(Instant.now());
    }

    private static Map<String, Object> view(ApprovalEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvalId", entity.getApprovalId());
        out.put("playbookId", entity.getPlaybookId());
        out.put("requestedBy", entity.getRequestedBy());
        out.put("approvedBy", entity.getApprovedBy());
        out.put("reason", entity.getReason());
        out.put("status", entity.getStatus());
        out.put("expiresAt", entity.getExpiresAt() == null ? null : entity.getExpiresAt().toString());
        out.put("createdAt", entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
        out.put("executionId", entity.getExecutionId());
        return out;
    }

    private static String write(Map<String, Object> scope) {
        try { return JSON.writeValueAsString(scope); }
        catch (Exception ex) { throw new IllegalArgumentException("approval scope is not JSON serializable", ex); }
    }

    private static Map<String, Object> read(String scope) {
        if (scope == null || scope.isBlank()) return Map.of();
        try { return JSON.readValue(scope, new TypeReference<>() { }); }
        catch (Exception ex) { throw new IllegalStateException("invalid approval scope", ex); }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
