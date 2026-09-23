package com.socp.search.config.api.controller;

import com.socp.search.config.api.request.ParseRuleRequest;
import com.socp.search.config.domain.ParseRule;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.error.api.PageResponse;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.persistence.store.ParseRuleStore;
import com.socp.search.config.service.ParsePreviewService;
import com.socp.search.config.service.ParseRuleExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParseRuleControllerTest {

    @Test
    void compilesValidRulesBeforeSavingAndRejectsInvalidRules() {
        ParseRuleStore store = mock(ParseRuleStore.class);
        when(store.create(any(ParseRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ParseRuleController controller = new ParseRuleController(
                store, mock(ParsePreviewService.class), new ParseRuleExecutor(new ParserRegistry()));

        ParseRuleRequest valid = new ParseRuleRequest(
                "auth", "source-1", "REGEX", "user=(?<user>\\S+)",
                List.of(new ParseRuleRequest.FieldMapping("user", "user", null)),
                List.of(), List.of(), true, 1);

        ParseRule saved = controller.create(valid).data();

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

        when(store.page("", PageRequest.of(0, 500))).thenReturn(new PageImpl<>(List.of()));

        assertThat(controller.list().data()).isEmpty();
    }

    @Test
    void pageUsesOneBasedEnvelopeAndBoundsSearchInput() {
        ParseRuleStore store = mock(ParseRuleStore.class);
        ParseRule rule = ParseRule.createWithId("a", "Auth parser", null, "KV", null,
                List.of(), List.of(), true, 1);
        when(store.page("AUTH", PageRequest.of(0, 20))).thenReturn(
                new PageImpl<>(List.of(rule), PageRequest.of(0, 20), 1));
        ParseRuleController controller = new ParseRuleController(store, mock(ParsePreviewService.class));
        @SuppressWarnings("unchecked")
        var page = (PageResponse<ParseRule>) controller.list(1, 20, " AUTH ").data();
        assertThat(page.items()).containsExactly(rule);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.page()).isEqualTo(1);
        assertThatThrownBy(() -> controller.list(0, 20, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.list(1, 20, "x".repeat(129)))
                .isInstanceOf(ResponseStatusException.class);
        when(store.getMany(List.of("a"))).thenReturn(List.of(rule));
        assertThat(controller.resolve(List.of("a", "a")).data()).containsExactly(rule);
        assertThatThrownBy(() -> controller.resolve(List.of()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.resolve(List.of("x".repeat(256))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void httpRoutesDistinguishPagedListBulkResolveAndSingleRule() throws Exception {
        ParseRuleStore store = mock(ParseRuleStore.class);
        ParseRule rule = ParseRule.createWithId("a", "Auth parser", null, "KV", null,
                List.of(), List.of(), true, 1);
        when(store.page("auth", PageRequest.of(0, 20))).thenReturn(
                new PageImpl<>(List.of(rule), PageRequest.of(0, 20), 1));
        when(store.getMany(List.of("a", "b"))).thenReturn(List.of(rule));
        when(store.get("a")).thenReturn(rule);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ParseRuleController(store, mock(ParsePreviewService.class))).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/parse-rules").param("page", "1").param("size", "20").param("q", "auth"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.items[0].id").value("a"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/parse-rules/batch/resolve").param("ids", "a,b"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data[0].id").value("a"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/parse-rules/a"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.id").value("a"));
    }
    @Test
    void updatingPreservesIdentityAndPreviewDoesNotPersist() {
        ParseRuleStore store = mock(ParseRuleStore.class);
        ParseRule original = ParseRule.create("original", null, "JSON", null, List.of(), List.of(), false, 1);
        when(store.get(original.id())).thenReturn(original);
        when(store.update(eq(original.id()), any())).thenAnswer(call -> {
            UnaryOperator<ParseRule> edit = call.getArgument(1);
            return edit.apply(original);
        });
        when(store.update(eq("missing"), any())).thenThrow(ApiException.notFound("Parse rule not found"));
        ParseRuleController controller = new ParseRuleController(store, mock(ParsePreviewService.class), new ParseRuleExecutor(new ParserRegistry()));
        ParseRuleRequest request = new ParseRuleRequest("edited", null, "JSON", null, List.of(), List.of(), false, 2);
        ParseRule updated = controller.update(original.id(), request).data();
        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
        assertThat(updated.name()).isEqualTo("edited");
        assertThat(controller.get(original.id()).data()).isEqualTo(original);
        org.mockito.Mockito.clearInvocations(store);
        var preview = controller.previewDraft(new ParseRuleController.DraftPreview(request, "{\"user\":\"alice\"}")).data();
        assertThat(preview.get("matched")).isEqualTo(true);
        org.mockito.Mockito.verifyNoInteractions(store);
        assertThatThrownBy(() -> controller.update("missing", request)).isInstanceOf(ApiException.class);
    }

}
