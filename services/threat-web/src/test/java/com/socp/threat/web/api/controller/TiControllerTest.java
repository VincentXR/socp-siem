package com.socp.threat.web.api.controller;

import com.socp.threat.web.api.request.IocImportRequest;
import com.socp.threat.web.api.request.IocRequest;
import com.socp.threat.web.domain.Ioc;
import com.socp.threat.web.persistence.store.IocStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TiControllerTest {

    @Mock
    private IocStore store;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"SENSITIVE_UPLOAD_MARKER\": broken}",
            "{\"objects\":[{\"type\":\"indicator\",\"pattern\":\"[process:name = 'SENSITIVE_UPLOAD_MARKER']\"}]}"
    })
    void stixRejectionDoesNotExposeParserOrUploadedContent(String bundle) {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> new TiController(store).importStix("fixture", bundle));
        org.assertj.core.api.Assertions.assertThat(failure.getReason()).isEqualTo("Invalid STIX bundle");
        org.assertj.core.api.Assertions.assertThat(failure.getCause()).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void invalidStixHttpEnvelopeContainsOnlyTheSafeClientMessage() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new TiController(store))
                .setControllerAdvice(new com.socp.platform.error.web.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/iocs/import/stix")
                        .contentType("application/stix+json").content("{\"SENSITIVE_UPLOAD_MARKER\": broken}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Invalid STIX bundle"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SENSITIVE_UPLOAD_MARKER"))));
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void createsNormalizedIndicatorFromValidatedRequest() {
        given(store.add(any(Ioc.class))).willAnswer(invocation -> invocation.getArgument(0));
        IocRequest request = new IocRequest(
                "DOMAIN", "Example.COM", "HIGH", "manual", "suspicious domain", null);

        Ioc created = new TiController(store).create(request).data();

        org.junit.jupiter.api.Assertions.assertEquals("example.com", created.value());
        org.junit.jupiter.api.Assertions.assertEquals("HIGH", created.severity());
        org.junit.jupiter.api.Assertions.assertTrue(created.tags().isEmpty());
        verify(store).add(created);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "not-an-ip", "localhost", "256.1.2.3", "127.1", "1.2.3.4/24", "01.2.3.4",
            "2001:db8:::1", "fe80::1%eth0", "[::1]", "::ffff:127.1"
    })
    void rejectsMalformedIpOnCreateAndUpdateWithoutWriting(String value) {
        TiController controller = new TiController(store);
        IocRequest request = new IocRequest("IP", value, "HIGH", "manual", "", null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.create(request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.update("existing", request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "0.0.0.0", "255.255.255.255", " 198.18.51.10 ", "::1", "2001:db8::1", "::ffff:192.0.2.1"
    })
    void acceptsLiteralIpv4AndIpv6(String value) {
        given(store.add(any(Ioc.class))).willAnswer(invocation -> invocation.getArgument(0));
        Ioc created = new TiController(store).create(new IocRequest("IP", value, "HIGH", "manual", "", null)).data();
        org.assertj.core.api.Assertions.assertThat(created.value()).isEqualTo(value.trim());
    }

    @Test
    void batchImportSkipsInvalidIpAndKeepsValidRows() {
        given(store.add(any(Ioc.class))).willAnswer(invocation -> invocation.getArgument(0));
        var rows = List.of(new IocImportRequest(null, "not-an-ip", null, null, null, null),
                new IocImportRequest("IP", "2001:db8::1", null, null, null, null));
        Map<String, Object> result = new TiController(store).importIocs(rows).data();
        org.assertj.core.api.Assertions.assertThat(result).containsEntry("imported", 1).containsEntry("skipped", 1);
        org.assertj.core.api.Assertions.assertThat(result.get("errors").toString()).contains("第 1 行", "IPv4", "IPv6");
        verify(store).add(any(Ioc.class));
    }

    @Test
    void invalidStixImportIsAClientErrorAndDoesNotPartiallyWrite() {
        String bundle = """
                {"objects":[{"type":"indicator","pattern":"[ipv4-addr:value = '192.0.2.1']"},
                {"type":"indicator","pattern":"[ipv4-addr:value = 'not-an-ip']"}]}
                """;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TiController(store).importStix("fixture", bundle))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void exposesListDeleteAndMatchResults() {
        Ioc hit = Ioc.of("IP", "203.0.113.10", "HIGH", "feed", "known C2", List.of("c2"));
        given(store.page("IP", 1, 500, "")).willReturn(new PageImpl<>(
                List.of(hit), PageRequest.of(0, 500), 1));
        given(store.delete(hit.id())).willReturn(true);
        given(store.match(hit.value())).willReturn(hit);
        TiController controller = new TiController(store);

        ApiResult<PageResponse<Ioc>> listed = controller.list("IP", 1, 500, "");
        org.junit.jupiter.api.Assertions.assertEquals(List.of(hit), listed.data().items());
        org.junit.jupiter.api.Assertions.assertEquals(
                Map.of("removed", true, "id", hit.id()), controller.delete(hit.id()).data());
        Map<String, Object> matched = controller.matchOne(hit.value()).data();
        org.junit.jupiter.api.Assertions.assertEquals(true, matched.get("matched"));
        org.junit.jupiter.api.Assertions.assertSame(hit, matched.get("ioc"));

        given(store.match("clean.example")).willReturn(null);
        Map<String, Object> missed = controller.matchOne("clean.example").data();
        org.junit.jupiter.api.Assertions.assertFalse((Boolean) missed.get("matched"));
        org.junit.jupiter.api.Assertions.assertFalse(missed.containsKey("ioc"));
    }

    @Test
    void summarizesIndicatorsByType() {
        Ioc ip = Ioc.of("IP", "203.0.113.10", "HIGH", "feed", "", List.of());
        Ioc domain = Ioc.of("DOMAIN", "example.test", "MEDIUM", "feed", "", List.of());
        given(store.count()).willReturn(3L);
        given(store.all()).willReturn(List.of(ip, ip, domain));

        Map<String, Object> stats = new TiController(store).stats().data();

        org.junit.jupiter.api.Assertions.assertEquals(3L, stats.get("total"));
        org.junit.jupiter.api.Assertions.assertEquals(
                Map.of("IP", 2L, "DOMAIN", 1L), stats.get("byType"));
    }

    @Test
    void importReportsImportedAndSkippedIndicators() {
        given(store.add(any(Ioc.class))).willAnswer(invocation -> invocation.getArgument(0));
        List<IocImportRequest> rows = List.of(
                new IocImportRequest("IP", "203.0.113.55", "HIGH", null, null, null),
                new IocImportRequest("DOMAIN", null, null, null, null, null));

        Map<String, Object> result = new TiController(store).importIocs(rows).data();

        org.junit.jupiter.api.Assertions.assertEquals(1, result.get("imported"));
        org.junit.jupiter.api.Assertions.assertEquals(1, result.get("skipped"));
        org.junit.jupiter.api.Assertions.assertTrue(((List<?>) result.get("errors")).get(0).toString().contains("缺少情报值"));
        verify(store).add(any(Ioc.class));
    }
}
