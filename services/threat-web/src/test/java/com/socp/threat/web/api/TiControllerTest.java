package com.socp.threat.web.api;

import com.socp.threat.web.domain.Ioc;
import com.socp.threat.web.store.IocStore;
import org.junit.jupiter.api.Test;
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

    @Test
    void createsNormalizedIndicatorFromValidatedRequest() {
        given(store.add(any(Ioc.class))).willAnswer(invocation -> invocation.getArgument(0));
        IocRequest request = new IocRequest(
                "DOMAIN", "Example.COM", "HIGH", "manual", "suspicious domain", null);

        Ioc created = new TiController(store).create(request);

        org.junit.jupiter.api.Assertions.assertEquals("example.com", created.value());
        org.junit.jupiter.api.Assertions.assertEquals("HIGH", created.severity());
        org.junit.jupiter.api.Assertions.assertTrue(created.tags().isEmpty());
        verify(store).add(created);
    }

    @Test
    void exposesListDeleteAndMatchResults() {
        Ioc hit = Ioc.of("IP", "203.0.113.10", "HIGH", "feed", "known C2", List.of("c2"));
        given(store.list("IP")).willReturn(List.of(hit));
        given(store.delete(hit.id())).willReturn(true);
        given(store.match(hit.value())).willReturn(hit);
        TiController controller = new TiController(store);

        org.junit.jupiter.api.Assertions.assertEquals(List.of(hit), controller.list("IP"));
        org.junit.jupiter.api.Assertions.assertEquals(
                Map.of("removed", true, "id", hit.id()), controller.delete(hit.id()));
        Map<String, Object> matched = controller.matchOne(hit.value());
        org.junit.jupiter.api.Assertions.assertEquals(true, matched.get("matched"));
        org.junit.jupiter.api.Assertions.assertSame(hit, matched.get("ioc"));

        given(store.match("clean.example")).willReturn(null);
        Map<String, Object> missed = controller.matchOne("clean.example");
        org.junit.jupiter.api.Assertions.assertFalse((Boolean) missed.get("matched"));
        org.junit.jupiter.api.Assertions.assertFalse(missed.containsKey("ioc"));
    }

    @Test
    void summarizesIndicatorsByType() {
        Ioc ip = Ioc.of("IP", "203.0.113.10", "HIGH", "feed", "", List.of());
        Ioc domain = Ioc.of("DOMAIN", "example.test", "MEDIUM", "feed", "", List.of());
        given(store.count()).willReturn(3L);
        given(store.all()).willReturn(List.of(ip, ip, domain));

        Map<String, Object> stats = new TiController(store).stats();

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

        Map<String, Object> result = new TiController(store).importIocs(rows);

        org.junit.jupiter.api.Assertions.assertEquals(1, result.get("imported"));
        org.junit.jupiter.api.Assertions.assertEquals(1, result.get("skipped"));
        org.junit.jupiter.api.Assertions.assertTrue(((List<?>) result.get("errors")).get(0).toString().contains("缺少情报值"));
        verify(store).add(any(Ioc.class));
    }
}
