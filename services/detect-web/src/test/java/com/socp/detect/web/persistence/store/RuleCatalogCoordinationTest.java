package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.repository.RuleContentConflictRepository;
import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.detect.web.persistence.repository.RuleRevisionRepository;
import com.socp.detect.web.persistence.repository.RuleChangeOutboxRepository;
import com.socp.detect.web.service.RuleChangePublisher;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.util.Json;
import com.socp.detect.web.model.RuleWriteCondition;
import com.socp.platform.error.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(30)
class RuleCatalogCoordinationTest {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected RuleRepository rules;
    @Autowired protected RuleRevisionRepository revisions;
    @Autowired protected RuleContentConflictRepository conflicts;
    @Autowired protected RuleChangeOutboxRepository outbox;

    @BeforeEach void prepare() {
        clearRows();
        TenantContext.set("catalog-a");
    }

    @AfterEach void cleanup() {
        TenantContext.clear();
        clearRows();
    }

    private void clearRows() {
        jdbc.update("delete from t_rule_change_outbox");
        jdbc.update("delete from t_rule_content_conflict");
        jdbc.update("delete from t_rule_revision");
        jdbc.update("delete from t_rule");
        jdbc.update("delete from t_rule_catalog");
    }

    private RuleCatalogCoordinator coordinator() { return new RuleCatalogCoordinator(jdbc, transactions); }
    private RuleSpecStore store() { return new RuleSpecStore(rules, revisions, conflicts, coordinator()); }
    private static RuleCatalogCoordinator.Pack pack(String version, char fingerprint) {
        return new RuleCatalogCoordinator.Pack("fixture", version, String.valueOf(fingerprint).repeat(64));
    }

    @Test void concurrentReplicasInstallCompleteCataloguesOnlyOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await();
                return TenantContext.callWith("catalog-a", () -> store().list());
            })).toList();
            start.countDown();
            int expected = ((List<?>) DetectionContentCatalog.manifest().get("rules")).size();
            for (var task : tasks) assertEquals(expected, task.get(15, TimeUnit.SECONDS).size());
            assertEquals(expected, rules.countByTenantId("catalog-a"));
            assertEquals(2, jdbc.queryForObject("select count(*) from t_rule_catalog where pack_fingerprint is not null", Integer.class));
        }
    }

    @Test void readersWaitForInstallationCommitAndFailedInstallationLeavesNoMarker() throws Exception {
        var first = coordinator();
        var second = coordinator();
        var pack = pack("1", 'a');
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var readerStarted = new CountDownLatch(1);
        var installations = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> first.withCatalog("install", pack, () -> {
                installations.incrementAndGet();
                entered.countDown();
                await(release);
            }, () -> "ready"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var reader = executor.submit(() -> {
                    readerStarted.countDown();
                    return second.withCatalog("install", pack, installations::incrementAndGet, () -> "ready");
                });
                assertTrue(readerStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> reader.get(200, TimeUnit.MILLISECONDS));
                assertEquals(0, jdbc.queryForObject("select count(*) from t_rule_catalog where tenant_id = 'install' and pack_fingerprint is not null", Integer.class));
                release.countDown();
                assertEquals("ready", writer.get(5, TimeUnit.SECONDS));
                assertEquals("ready", reader.get(5, TimeUnit.SECONDS));
                assertEquals(1, installations.get());
            } finally { release.countDown(); }
        }

        assertThrows(IllegalStateException.class, () -> first.withCatalog("failed", pack, () -> {
            throw new IllegalStateException("broken content");
        }, () -> "unreachable"));
        assertEquals(0, jdbc.queryForObject("select count(*) from t_rule_catalog where tenant_id = 'failed'", Integer.class));
        assertEquals("recovered", second.withCatalog("failed", pack, () -> { }, () -> "recovered"));
    }

    @Test void markerAndNamespaceLockParticipateInTheOwnersTransaction() throws Exception {
        var first = coordinator();
        var second = coordinator();
        var pack = pack("1", 'a');
        var ownerEntered = new CountDownLatch(1);
        var releaseOwner = new CountDownLatch(1);
        var installs = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                first.withCatalog("owner", pack, installs::incrementAndGet, () -> null);
                ownerEntered.countDown();
                await(releaseOwner);
                status.setRollbackOnly();
                return null;
            }));
            try {
                assertTrue(ownerEntered.await(5, TimeUnit.SECONDS));
                var contender = executor.submit(() -> second.withCatalog("owner", pack, installs::incrementAndGet, () -> true));
                assertThrows(TimeoutException.class, () -> contender.get(200, TimeUnit.MILLISECONDS));
                releaseOwner.countDown();
                owner.get(5, TimeUnit.SECONDS);
                assertTrue(contender.get(5, TimeUnit.SECONDS));
                assertEquals(2, installs.get(), "rolled-back installation must run again in the committed transaction");
            } finally { releaseOwner.countDown(); }
        }
    }

    @Test void newerOrModifiedSameVersionContentCannotBeSilentlyDowngraded() {
        var catalog = coordinator();
        var installs = new AtomicInteger();
        catalog.withCatalog("versions", pack("2026.9.9", 'a'), installs::incrementAndGet, () -> null);
        catalog.withCatalog("versions", pack("2026.9.10", 'b'), installs::incrementAndGet, () -> null);
        assertThrows(IllegalStateException.class, () -> catalog.withCatalog("versions", pack("2026.9.9", 'a'), installs::incrementAndGet, () -> null));
        assertThrows(IllegalStateException.class, () -> catalog.withCatalog("versions", pack("2026.9.10", 'c'), installs::incrementAndGet, () -> null));
        assertEquals(2, installs.get());
        assertEquals("2026.9.10", jdbc.queryForObject("select pack_version from t_rule_catalog where tenant_id = 'versions'", String.class));
    }

    @Test void installationMarkersCannotBePartiallyWritten() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("insert into t_rule_catalog (tenant_id, pack_version) values ('partial', '2027.1.1')"));
        assertEquals(0, jdbc.queryForObject("select count(*) from t_rule_catalog where tenant_id = 'partial'", Integer.class));
    }

    @Test void concurrentEditsAllocateAnOrderedRevisionChainAcrossStoreInstances() throws Exception {
        var first = store();
        var second = store();
        first.save(spec("custom", "initial"));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> { start.await(); return TenantContext.callWith("catalog-a", () -> first.save(spec("custom", "one"))); });
            var two = executor.submit(() -> { start.await(); return TenantContext.callWith("catalog-a", () -> second.save(spec("custom", "two"))); });
            start.countDown();
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        }
        var chain = revisions.findByTenantIdAndRuleIdOrderByRevisionAsc("catalog-a", "custom",
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent();
        assertEquals(List.of(1L, 2L, 3L), chain.stream().map(row -> row.getRevision()).toList());
        assertEquals(Set.of("one", "two"), chain.subList(1, 3).stream()
                .map(row -> String.valueOf(Json.parseObject(row.getSpec()).get("name"))).collect(java.util.stream.Collectors.toSet()));
        assertEquals(first.revision("custom", chain.getLast().getRevision()).get("spec"), first.get("custom"));
    }

    @Test void ruleRevisionOutboxAndFirstInstallationRollBackTogether() {
        var store = store();
        var publisher = new RuleChangePublisher(outbox);
        var owner = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> owner.execute(status -> {
            store.save(spec("atomic", "atomic"));
            publisher.publish("atomic", "add");
            throw new IllegalStateException("owner failed after publishing");
        }));
        assertEquals(0, rules.countByTenantId("catalog-a"));
        assertEquals(0, revisions.maxRevision("catalog-a", "atomic"));
        assertEquals(0, jdbc.queryForObject("select count(*) from t_rule_change_outbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from t_rule_catalog where tenant_id = 'catalog-a'", Integer.class));
        owner.execute(status -> { store.save(spec("atomic", "atomic")); publisher.publish("atomic", "add"); return null; });
        assertEquals(1, revisions.maxRevision("catalog-a", "atomic"));
        assertEquals(1, jdbc.queryForObject("select count(*) from t_rule_change_outbox", Integer.class));
    }

    @Test void deletedPackagedRulesStayDeletedAcrossReplicasAndPackRefreshes() {
        var first = store();
        assertNotNull(first.get("AUTH-BRUTE"));
        assertTrue(first.delete("AUTH-BRUTE"));
        assertNull(store().get("AUTH-BRUTE"));
        jdbc.update("update t_rule_catalog set pack_version = '2026.01.01', pack_fingerprint = ? where tenant_id = ?",
                "0".repeat(64), "catalog-a");
        assertNull(store().get("AUTH-BRUTE"));
        assertEquals(1, revisions.maxRevision("catalog-a", "AUTH-BRUTE"));
        assertNotNull(first.restoreRevision("AUTH-BRUTE", 1));
        assertEquals(2, revisions.maxRevision("catalog-a", "AUTH-BRUTE"));
    }

    @Test void failedRevisionWriteDoesNotCommitTheRuleBody() {
        var first = store();
        first.save(spec("atomic", "before"));
        var failing = org.mockito.Mockito.mock(RuleRevisionRepository.class, org.mockito.AdditionalAnswers.delegatesTo(revisions));
        org.mockito.Mockito.doThrow(new IllegalStateException("revision unavailable")).when(failing).save(org.mockito.ArgumentMatchers.any());
        var store = new RuleSpecStore(rules, failing, conflicts, coordinator());
        assertThrows(IllegalStateException.class, () -> store.save(spec("atomic", "after")));
        assertEquals("before", first.get("atomic").get("name"));
        assertEquals(1, revisions.maxRevision("catalog-a", "atomic"));
    }

    @Test void packUpgradeWaitsForAnAnalystAndPreservesCommittedCustomization() throws Exception {
        var first = store();
        first.get("AUTH-BRUTE");
        String packId = String.valueOf(DetectionContentCatalog.manifest().get("packId"));
        var oldPack = new RuleCatalogCoordinator.Pack(packId, "2026.01.01", "0".repeat(64));
        jdbc.update("update t_rule_catalog set pack_version = ?, pack_fingerprint = ? where tenant_id = ?",
                oldPack.version(), oldPack.fingerprint(), "catalog-a");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> TenantContext.callWith("catalog-a", () ->
                    coordinator().withCatalog("catalog-a", oldPack, () -> fail("old catalogue already installed"), () -> {
                        var entity = rules.findByRuleIdAndTenantId("AUTH-BRUTE", "catalog-a").orElseThrow();
                        var edited = Json.parseObject(entity.getSpec());
                        edited.put("name", "analyst tuning");
                        edited.put("contentCustomized", true);
                        edited.put("contentVersion", oldPack.version());
                        try { entity.setSpec(Json.mapper().writeValueAsString(edited)); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                        rules.saveAndFlush(entity);
                        entered.countDown();
                        await(release);
                        return null;
                    })));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var upgrader = executor.submit(() -> TenantContext.callWith("catalog-a", () -> store().get("AUTH-BRUTE")));
                assertThrows(TimeoutException.class, () -> upgrader.get(200, TimeUnit.MILLISECONDS));
                release.countDown();
                writer.get(5, TimeUnit.SECONDS);
                var upgraded = upgrader.get(5, TimeUnit.SECONDS);
                assertEquals("analyst tuning", upgraded.get("name"));
                assertEquals(true, upgraded.get("contentCustomized"));
                assertEquals(oldPack.version(), upgraded.get("contentVersion"));
                assertEquals(1, conflicts.findByTenantIdAndStatus("catalog-a", "PENDING",
                        org.springframework.data.domain.PageRequest.of(0, 100)).getTotalElements());
            } finally { release.countDown(); }
        }
    }

    private static Map<String, Object> spec(String id, String name) {
        return Map.of("id", id, "name", name, "type", "pattern", "severity", "HIGH", "version", "1",
                "owner", "analyst", "status", "TESTING", "match", List.of(Map.of("field", "msg", "op", "contains", "value", "attack")));
    }

    private static RuleWriteCondition head(Map<String, Object> current) {
        return RuleWriteCondition.parse(RuleWriteCondition.etag(current), null, false);
    }

    @Test void concurrentConditionalWritersHaveOneWinnerAndOneDurableRevisionAndOutbox() throws Exception {
        var first = store();
        var second = store();
        var original = first.create(spec("conditional", "before"));
        var condition = head(original);
        var publisher = new RuleChangePublisher(outbox);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.of(first, second).stream().map(writer -> executor.submit(() -> {
                start.await();
                return TenantContext.callWith("catalog-a", () -> {
                    try {
                        new TransactionTemplate(transactions).execute(status -> {
                            writer.update(spec("conditional", "winner"), condition);
                            publisher.publish("conditional", "update");
                            return null;
                        });
                        return 200;
                    } catch (ApiException conflict) { return conflict.getCode(); }
                });
            })).toList();
            start.countDown();
            var outcomes = new java.util.ArrayList<Integer>();
            for (var task : tasks) outcomes.add(task.get(15, TimeUnit.SECONDS));
            outcomes.sort(Integer::compareTo);
            assertEquals(List.of(200, 412), outcomes);
        }
        assertEquals("winner", first.get("conditional").get("name"));
        assertEquals(2, revisions.maxRevision("catalog-a", "conditional"));
        assertEquals(1, jdbc.queryForObject("select count(*) from t_rule_change_outbox", Integer.class));
        assertNotEquals(original.get("revisionToken"), second.get("conditional").get("revisionToken"));
    }

    @Test void staleDeletesAndRestoresCannotOverwriteANewerHeadOrResurrectADeletedRule() {
        var store = store();
        var first = store.create(spec("restore-cas", "first"));
        var second = store.update(spec("restore-cas", "second"), head(first));
        assertEquals(412, assertThrows(ApiException.class, () -> store.delete("restore-cas", head(first))).getCode());
        assertEquals(412, assertThrows(ApiException.class, () -> store.restoreRevision("restore-cas", 1, head(first))).getCode());
        var restored = store.restoreRevision("restore-cas", 1, head(second));
        assertEquals("first", restored.get("name"));
        assertNotEquals(first.get("revisionToken"), restored.get("revisionToken"));
        assertEquals(412, assertThrows(ApiException.class, () -> store.update(first, head(first))).getCode());
        assertTrue(store.delete("restore-cas", head(restored)));
        assertEquals(412, assertThrows(ApiException.class, () -> store.update(second, head(restored))).getCode());
        assertNull(store.get("restore-cas"));
        var absent = RuleWriteCondition.parse(null, "*", true);
        var recreated = store.restoreRevision("restore-cas", 1, absent);
        assertNotEquals(restored.get("revisionToken"), recreated.get("revisionToken"));
        assertEquals(412, assertThrows(ApiException.class, () -> store.restoreRevision("restore-cas", 2, absent)).getCode());
        assertEquals(409, assertThrows(ApiException.class, () -> store.create(spec("restore-cas", "overwrite"))).getCode());
        assertEquals("first", store.get("restore-cas").get("name"));
        assertEquals(5, revisions.maxRevision("catalog-a", "restore-cas"));
    }

    @Test void conditionalLifecycleCannotActivateThroughAnOmittedStatusOrDeleteActiveRules() {
        var store = store();
        var draft = store.create(spec("lifecycle-cas", "draft"));
        var active = store.activate("lifecycle-cas", head(draft));
        assertEquals(412, assertThrows(ApiException.class, () -> store.activate("lifecycle-cas", head(draft))).getCode());
        assertEquals(409, assertThrows(ApiException.class, () -> store.delete("lifecycle-cas", head(active))).getCode());
        var disable = new java.util.LinkedHashMap<>(active);
        disable.remove("status");
        disable.put("enabled", false);
        var disabled = store.update(disable, head(active));
        assertEquals("DISABLED", disabled.get("status"));
        var bypass = new java.util.LinkedHashMap<>(disabled);
        bypass.remove("status");
        bypass.put("enabled", true);
        assertEquals(403, assertThrows(ApiException.class, () -> store.update(bypass, head(disabled))).getCode());
        assertEquals(disabled, store.get("lifecycle-cas"));
        var archive = new java.util.LinkedHashMap<>(disabled);
        archive.put("status", "ARCHIVED");
        var archived = store.update(archive, head(disabled));
        assertEquals(409, assertThrows(ApiException.class, () -> store.activate("lifecycle-cas", head(archived))).getCode());
    }

    @Test void tokensAreTenantScopedStableOnReadAndCannotBeChosenByThePayload() {
        var store = store();
        var first = store.create(spec("tenant-cas", "equal content"));
        // Identical legacy bodies have stable tokens without a migration, and
        // even these tokens are bound to the owning tenant.
        var legacy = spec("legacy-cas", "legacy");
        for (String tenant : List.of("catalog-a", "catalog-b")) {
            TenantContext.runWith(tenant, () -> {
                store.count();
                var entity = new com.socp.detect.web.persistence.entity.RuleEntity();
                entity.setId("legacy-cas"); entity.setStorageId(tenant + "-legacy"); entity.setTenantId(tenant);
                entity.setSpec("{\"id\":\"legacy-cas\",\"name\":\"legacy\",\"type\":\"pattern\",\"severity\":\"HIGH\",\"status\":\"TESTING\"}");
                rules.saveAndFlush(entity);
            });
        }
        var legacyA = store.get("legacy-cas");
        assertEquals(legacyA, store.get("legacy-cas"));
        var legacyB = TenantContext.callWith("catalog-b", () -> store.get("legacy-cas"));
        assertNotEquals(legacyA.get("revisionToken"), legacyB.get("revisionToken"));
        TenantContext.runWith("catalog-b", () -> assertEquals(412,
                assertThrows(ApiException.class, () -> store.update(legacy, head(legacyA))).getCode()));
        var forged = new java.util.LinkedHashMap<>(first);
        forged.put("revisionToken", "chosen-by-client");
        var saved = store.update(forged, head(first));
        assertNotEquals("chosen-by-client", saved.get("revisionToken"));
        assertNotEquals(first.get("revisionToken"), saved.get("revisionToken"));
        assertEquals(new com.socp.rule.config.RuleSpec(first).stateSemanticsFingerprint(),
                new com.socp.rule.config.RuleSpec(saved).stateSemanticsFingerprint());
        assertEquals(saved, store.get("tenant-cas"));
        assertEquals(saved, store.page(1, 500).getContent().stream().filter(row -> "tenant-cas".equals(row.get("id"))).findFirst().orElseThrow());
    }

    @Test void aFailedOwnerTransactionDoesNotAdvanceTheVersionOrKeepItsOutbox() {
        var store = store();
        var initial = store.create(spec("rolled-back-cas", "before"));
        var publisher = new RuleChangePublisher(outbox);
        new TransactionTemplate(transactions).execute(status -> {
            store.update(spec("rolled-back-cas", "uncommitted"), head(initial));
            publisher.publish("rolled-back-cas", "update");
            status.setRollbackOnly();
            return null;
        });
        assertEquals(initial, store.get("rolled-back-cas"));
        assertEquals(1, revisions.maxRevision("catalog-a", "rolled-back-cas"));
        assertEquals(0, jdbc.queryForObject("select count(*) from t_rule_change_outbox", Integer.class));
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture release timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
