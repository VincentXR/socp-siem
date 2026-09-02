package com.socp.search.config.api.controller;

import com.socp.search.config.api.request.ParseRuleRequest;
import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.persistence.store.ParseRuleStore;
import com.socp.search.config.service.ParsePreviewService;
import com.socp.search.config.service.ParseRuleExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParseRuleControllerTest {

    @Test
    void compilesValidRulesBeforeSavingAndRejectsInvalidRules() {
        ParseRuleStore store = mock(ParseRuleStore.class);
        when(store.save(any(ParseRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ParseRuleController controller = new ParseRuleController(
                store, mock(ParsePreviewService.class), new ParseRuleExecutor(new ParserRegistry()));

        ParseRuleRequest valid = new ParseRuleRequest(
                "auth", "source-1", "REGEX", "user=(?<user>\\S+)",
                List.of(new ParseRuleRequest.FieldMapping("user", "user", null)),
                List.of(), List.of(), true, 1);

        ParseRule saved = controller.create(valid);

        assertThat(saved.name()).isEqualTo("auth");
        assertThat(saved.sourceId()).isEqualTo("source-1");

        ParseRuleRequest invalid = new ParseRuleRequest(
                "broken", null, "REGEX", "[", List.of(), List.of(), List.of(), true, 1);
        assertThatThrownBy(() -> controller.create(invalid))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid parse rule");
    }

    @Test
    void retainsTheLightweightConstructorForExistingCallers() {
        ParseRuleStore store = mock(ParseRuleStore.class);
        ParseRuleController controller = new ParseRuleController(store, mock(ParsePreviewService.class));

        when(store.list()).thenReturn(List.of());

        assertThat(controller.list()).isEmpty();
    }
}
