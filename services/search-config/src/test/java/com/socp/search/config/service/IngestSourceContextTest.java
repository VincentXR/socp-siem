package com.socp.search.config.service;

import com.socp.search.config.domain.ParseFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestSourceContextTest {

    @Test
    void unresolvedContextUsesSafeDefaults() {
        IngestSourceContext context = IngestSourceContext.unresolved("collector-1", null);

        assertThat(context.collectorId()).isEqualTo("collector-1");
        assertThat(context.sourceId()).isNull();
        assertThat(context.format()).isEqualTo(ParseFormat.AUTO);
        assertThat(context.parseRuleIds()).isEmpty();
        assertThat(context.resolved()).isFalse();
        assertThat(context.hasExplicitRules()).isFalse();
    }
}
