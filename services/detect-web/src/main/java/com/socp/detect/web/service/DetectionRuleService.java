package com.socp.detect.web.service;

import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.detect.web.model.RuleWriteCondition;
import com.socp.platform.error.exception.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/** Rule catalogue mutations and lifecycle transitions, independent of engine recovery. */
@Service
public class DetectionRuleService {

    private final RuleSpecStore store;
    private final RuleChangePublisher publisher;
    private final DetectionEngineLifecycle lifecycle;
    private TransactionTemplate committedReloadTransaction;

    public DetectionRuleService(RuleSpecStore store, RuleChangePublisher publisher,
                                DetectionEngineLifecycle lifecycle) {
        this.store = store;
        this.publisher = publisher;
        this.lifecycle = lifecycle;
    }

    public List<Map<String, Object>> listRules() {
        return store.list();
    }

    public List<Map<String, Object>> listRules(int limit) {
        return store.list(limit);
    }

    public long ruleCount() {
        return store.count();
    }

    public Page<Map<String, Object>> listRulesPage(int page, int size) {
        return store.page(page, size);
    }

    public Page<Map<String, Object>> searchRules(int page, int size, String keyword, String status,
                                                 String reference, String alias) {
        return store.search(page, size, keyword, status, reference, alias);
    }

    public Page<Map<String, Object>> searchRulesByTechnique(int page, int size, String keyword, String status,
                                                           String reference, String alias, String technique) {
        return store.searchTechnique(page, size, keyword, status, reference, alias, technique);
    }

    public Page<Map<String, Object>> ruleOptions(int page, int size, String keyword) {
        return store.options(page, size, keyword);
    }

    public List<Map<String, Object>> lookupRules(List<String> ids) { return store.lookup(ids); }

    public Page<Map<String, Object>> rulesByTechnique(String technique, int page, int size) {
        return store.byTechnique(technique, page, size);
    }

    public List<String> activeRuleTechniques() { return store.activeTechniques(); }

    public Map<String, Object> getRule(String id) {
        Map<String, Object> rule = store.get(id);
        if (rule == null) throw ApiException.notFound("rule not found: " + id);
        return rule;
    }

    public Map<String, Object> contentManifest() {
        return store.contentManifest();
    }

    @Transactional
    public Map<String, Object> addRule(Map<String, Object> spec) {
        Map<String, Object> saved = store.create(spec);
        publisher.publish(String.valueOf(saved.get("id")), "add");
        reloadAfterCommit();
        return saved;
    }

    @Transactional
    public Map<String, Object> updateRule(Map<String, Object> spec, RuleWriteCondition condition) {
        Map<String, Object> saved = store.update(spec, condition);
        publisher.publish(String.valueOf(saved.get("id")), "update");
        reloadAfterCommit();
        return saved;
    }

    @Transactional
    public Map<String, Object> activateRule(String id, RuleWriteCondition condition) {
        Map<String, Object> saved = store.activate(id, condition);
        publisher.publish(id, "update");
        reloadAfterCommit();
        return saved;
    }

    @Transactional
    public boolean deleteRule(String id, RuleWriteCondition condition) {
        boolean removed = condition == null ? store.delete(id) : store.delete(id, condition);
        if (removed) {
            publisher.publish(id, "delete");
            reloadAfterCommit();
        }
        return removed;
    }

    public List<Map<String, Object>> listRuleRevisions(String id) {
        return store.revisions(id);
    }

    public List<Map<String, Object>> ruleContentConflicts() {
        return store.contentConflicts();
    }

    public Page<Map<String, Object>> ruleRevisionPage(String id, int page, int size) {
        return store.revisionPage(id, page, size);
    }

    public Map<String, Object> ruleRevision(String id, long revision) {
        Map<String, Object> result = store.revision(id, revision);
        if (result == null) throw ApiException.notFound("rule revision not found: " + id + "#" + revision);
        return result;
    }

    public Page<Map<String, Object>> ruleContentConflictPage(int page, int size) {
        return store.contentConflictPage(page, size);
    }

    @Transactional
    public Map<String, Object> restoreRuleRevision(String id, long revision, RuleWriteCondition condition) {
        Map<String, Object> restored = store.restoreRevision(id, revision, condition);
        if (restored == null) {
            throw ApiException.notFound("规则版本不存在: " + id + "#" + revision);
        }
        publisher.publish(id, "restore");
        reloadAfterCommit();
        return restored;
    }

    @Transactional
    public Map<String, Object> updateRule(Map<String, Object> spec) {
        return updateRule(spec, null);
    }

    @Transactional
    public Map<String, Object> activateRule(String id) {
        return activateRule(id, null);
    }

    @Transactional
    public boolean deleteRule(String id) {
        return deleteRule(id, null);
    }

    @Transactional
    public Map<String, Object> restoreRuleRevision(String id, long revision) {
        return restoreRuleRevision(id, revision, null);
    }

    public void reload() { lifecycle.reload(); }

    @Autowired
    void configureCommittedReloadTransaction(PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committedReloadTransaction = transaction;
    }

    private void reloadAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            lifecycle.reload();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // The completed write still has resources bound here. Catalogue
                // rebuilds lock rows, so they require their own fresh transaction.
                var transaction = committedReloadTransaction;
                if (transaction == null) lifecycle.reload();
                else transaction.executeWithoutResult(status -> lifecycle.reload());
            }
        });
    }
}
