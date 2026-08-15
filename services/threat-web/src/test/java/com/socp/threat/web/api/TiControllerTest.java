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
    void importReportsImportedAndSkippedIndicators() {
        given(store.add(any(Ioc.class))).willAnswer(invocation -> invocation.getArgument(0));
        List<Map<String, Object>> rows = List.of(
                Map.of("type", "IP", "value", "203.0.113.55", "severity", "HIGH"),
                Map.of("type", "DOMAIN"));

        Map<String, Object> result = new TiController(store).importIocs(rows);

        org.junit.jupiter.api.Assertions.assertEquals(1, result.get("imported"));
        org.junit.jupiter.api.Assertions.assertEquals(1, result.get("skipped"));
        org.junit.jupiter.api.Assertions.assertTrue(((List<?>) result.get("errors")).get(0).toString().contains("缺少情报值"));
        verify(store).add(any(Ioc.class));
    }
}
