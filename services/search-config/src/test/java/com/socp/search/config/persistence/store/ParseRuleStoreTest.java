package com.socp.search.config.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.domain.ParseRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParseRuleStoreTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void revisionsInvalidateTheCompiledRuleCacheAfterSaveAndDelete() {
        TenantContext.set("tenant-parse-rule-store");
        ParseRuleStore store = new ParseRuleStore();
        long seededRevision = store.revision("tenant-parse-rule-store");
        ParseRule rule = ParseRule.createWithId(
                "custom-rule", "Custom rule", null, "KV", null,
                List.of(), List.of(), true, 30);

        assertThat(store.save(rule)).isEqualTo(rule);
        assertThat(store.revision("tenant-parse-rule-store")).isEqualTo(seededRevision + 1);
        assertThat(store.get(rule.id())).isEqualTo(rule);

        assertThat(store.delete(rule.id())).isTrue();
        assertThat(store.revision("tenant-parse-rule-store")).isEqualTo(seededRevision + 2);
        assertThat(store.delete(rule.id())).isFalse();
    }

    @Test
    void tenantLimitRejectsNewRulesButAllowsExistingEditsAndReplacements() {
        TenantContext.set("tenant-rule-limit");
        ParseRuleStore store = new ParseRuleStore();
        for (int index = 0; index < 510; index++) {
            store.create(rule("scoped-" + index, "source-a"));
        }
        assertThat(store.list()).hasSize(512);
        assertThatThrownBy(() -> store.create(rule("overflow", "source-a")))
                .isInstanceOf(ApiException.class).hasMessageContaining("512");
        assertThat(store.update("scoped-0", old -> new ParseRule(old.id(), "edited", old.sourceId(),
                old.format(), old.pattern(), old.mapping(), old.setFields(), old.filters(),
                old.enabled(), old.order(), old.createdAt())).name()).isEqualTo("edited");
        assertThat(store.delete("scoped-1")).isTrue();
        assertThat(store.create(rule("replacement", "source-a")).id()).isEqualTo("replacement");
        assertThat(store.list()).hasSize(512);
    }

    @Test
    void globalFallbackAndSerializedRulePayloadHaveSeparateBounds() {
        TenantContext.set("tenant-global-rule-limit");
        ParseRuleStore store = new ParseRuleStore();
        for (int index = 0; index < 30; index++) {
            store.create(rule("global-" + index, null));
        }
        assertThatThrownBy(() -> store.create(rule("global-overflow", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("32");
        ParseRule scoped = store.create(rule("scoped", "source-a"));
        assertThatThrownBy(() -> store.update(scoped.id(), old -> new ParseRule(old.id(), old.name(),
                null, old.format(), old.pattern(), old.mapping(), old.setFields(), old.filters(),
                old.enabled(), old.order(), old.createdAt())))
                .isInstanceOf(ApiException.class).hasMessageContaining("32");
        assertThatThrownBy(() -> store.create(ParseRule.createWithId("large", "large", "source-a",
                "KV", "a".repeat(65_536), List.of(), List.of(), true, 40)))
                .isInstanceOf(ApiException.class).hasMessageContaining("serialized bytes");
        assertThat(store.delete("global-0")).isTrue();
        assertThat(store.create(rule("global-replacement", null)).id()).isEqualTo("global-replacement");
    }

    @Test
    void pagedNameSearchAndSelectedIdsStayScopedAndOrdered() {
        ParseRuleStore store = new ParseRuleStore();
        TenantContext.set("tenant-rule-page");
        store.create(ParseRule.createWithId("b", "CRITICAL beta", "source-b", "KV", null,
                List.of(), List.of(), true, 10));
        store.create(ParseRule.createWithId("a", "Critical alpha", "source-a", "KV", null,
                List.of(), List.of(), true, 10));
        assertThat(store.page("critical", PageRequest.of(0, 1)).getContent())
                .extracting(ParseRule::id).containsExactly("a");
        assertThat(store.page("CRITICAL", PageRequest.of(1, 1)).getContent())
                .extracting(ParseRule::id).containsExactly("b");
        assertThat(store.getMany(List.of("b", "missing", "a", "sshd-auth-failed")))
                .extracting(ParseRule::id).containsExactly("b", "a", "sshd-auth-failed");
        TenantContext.set("tenant-other");
        assertThat(store.getMany(List.of("a", "sshd-auth-failed")))
                .extracting(ParseRule::id).containsExactly("sshd-auth-failed");
    }

    private static ParseRule rule(String id, String sourceId) {
        return ParseRule.createWithId(id, id, sourceId, "KV", null,
                List.of(), List.of(), true, 10);
    }
}
