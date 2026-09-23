package com.socp.detect.web.service;

import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.detect.web.model.RuleWriteCondition;
import com.socp.platform.error.exception.ApiException;
import org.springframework.data.domain.Page;

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

    Page<Map<String, Object>> searchRules(int page, int size, String keyword, String status,
                                          String reference, String alias) {
        return store.search(page, size, keyword, status, reference, alias);
    }

    Page<Map<String, Object>> ruleOptions(int page, int size, String keyword) {
        return store.options(page, size, keyword);
    }

    List<Map<String, Object>> lookupRules(List<String> ids) { return store.lookup(ids); }

    List<String> activeTechniques() { return store.activeTechniques(); }

    Map<String, Object> getRule(String id) {
        Map<String, Object> rule = store.get(id);
        if (rule == null) throw ApiException.notFound("rule not found: " + id);
        return rule;
    }

    Map<String, Object> contentManifest() {
        return store.contentManifest();
    }

    Map<String, Object> addRule(Map<String, Object> spec) {
        Map<String, Object> saved = store.create(spec);
        publisher.publish(String.valueOf(saved.get("id")), "add");
        return saved;
    }

    Map<String, Object> updateRule(Map<String, Object> spec, RuleWriteCondition condition) {
        Map<String, Object> saved = store.update(spec, condition);
        publisher.publish(String.valueOf(saved.get("id")), "update");
        return saved;
    }

    Map<String, Object> activateRule(String id, RuleWriteCondition condition) {
        Map<String, Object> saved = store.activate(id, condition);
        publisher.publish(id, "update");
        return saved;
    }

    boolean deleteRule(String id, RuleWriteCondition condition) {
        boolean removed = condition == null ? store.delete(id) : store.delete(id, condition);
        if (removed) publisher.publish(id, "delete");
        return removed;
    }

    List<Map<String, Object>> listRevisions(String id) {
        return store.revisions(id);
    }

    List<Map<String, Object>> contentConflicts() {
        return store.contentConflicts();
    }

    Page<Map<String, Object>> revisionPage(String id, int page, int size) {
        return store.revisionPage(id, page, size);
    }

    Map<String, Object> revision(String id, long revision) {
        Map<String, Object> result = store.revision(id, revision);
        if (result == null) throw ApiException.notFound("rule revision not found: " + id + "#" + revision);
        return result;
    }

    Page<Map<String, Object>> contentConflictPage(int page, int size) {
        return store.contentConflictPage(page, size);
    }

    Map<String, Object> restoreRevision(String id, long revision, RuleWriteCondition condition) {
        Map<String, Object> restored = store.restoreRevision(id, revision, condition);
        if (restored == null) {
            throw ApiException.notFound("规则版本不存在: " + id + "#" + revision);
        }
        publisher.publish(id, "restore");
        return restored;
    }
}
