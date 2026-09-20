package com.socp.detect.web.service;

import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.platform.error.exception.ApiException;
import org.springframework.data.domain.Page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rule catalogue mutations and lifecycle transitions, independent of engine recovery. */
final class DetectionRuleService {

    private final RuleSpecStore store;
    private final RuleChangePublisher publisher;

    DetectionRuleService(RuleSpecStore store, RuleChangePublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    List<Map<String, Object>> listRules() {
        return store.list();
    }

    List<Map<String, Object>> listRules(int limit) {
        return store.list(limit);
    }

    long ruleCount() {
        return store.count();
    }

    Page<Map<String, Object>> listRulesPage(int page, int size) {
        return store.page(page, size);
    }

    Map<String, Object> contentManifest() {
        return store.contentManifest();
    }

    Map<String, Object> addRule(Map<String, Object> spec) {
        Map<String, Object> saved = store.save(spec);
        publisher.publish(String.valueOf(saved.get("id")), "add");
        return saved;
    }

    Map<String, Object> updateRule(Map<String, Object> spec) {
        String id = String.valueOf(spec.get("id"));
        Map<String, Object> current = store.get(id);
        if (current == null) {
            throw ApiException.notFound("规则不存在: " + spec.get("id"));
        }

        Map<String, Object> updated = new LinkedHashMap<>(spec);
        if (!updated.containsKey("status") && current.get("status") != null) {
            updated.put("status", current.get("status"));
        }
        if (Boolean.FALSE.equals(updated.get("enabled"))
                && "ACTIVE".equalsIgnoreCase(String.valueOf(current.get("status")))) {
            updated.put("status", "DISABLED");
        }

        Map<String, Object> saved = store.save(updated);
        publisher.publish(String.valueOf(saved.get("id")), "update");
        return saved;
    }

    Map<String, Object> activateRule(String id) {
        Map<String, Object> current = store.get(id);
        if (current == null) {
            throw ApiException.notFound("规则不存在: " + id);
        }
        Map<String, Object> activated = new LinkedHashMap<>(current);
        activated.put("status", "ACTIVE");
        activated.put("enabled", true);
        return updateRule(activated);
    }

    boolean deleteRule(String id) {
        boolean removed = store.delete(id);
        if (removed) {
            publisher.publish(id, "delete");
        }
        return removed;
    }
}
