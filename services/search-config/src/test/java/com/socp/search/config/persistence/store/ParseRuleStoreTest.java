package com.socp.search.config.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.ParseRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParseRuleStoreTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void revisionsInvalidateTheCompiledRuleCacheAfterSaveAndDelete() {
        TenantContext.set("tenant-parse-rule-store");
        ParseRuleStore store = new ParseRuleStore();
        long seededRevision = store.revision();
        ParseRule rule = ParseRule.createWithId(
                "custom-rule", "Custom rule", null, "KV", null,
                List.of(), List.of(), true, 30);

        assertThat(store.save(rule)).isEqualTo(rule);
        assertThat(store.revision()).isEqualTo(seededRevision + 1);
        assertThat(store.get(rule.id())).isEqualTo(rule);

        assertThat(store.delete(rule.id())).isTrue();
        assertThat(store.revision()).isEqualTo(seededRevision + 2);
        assertThat(store.delete(rule.id())).isFalse();
    }
}
