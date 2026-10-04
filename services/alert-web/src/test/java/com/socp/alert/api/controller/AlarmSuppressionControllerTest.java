package com.socp.alert.api.controller;

import com.socp.alert.service.AlarmSuppressionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlarmSuppressionControllerTest {
    @Test
    void httpContractPreservesExplicitScopeAndDurationWithoutInferringRuleWide() throws Exception {
        var service = mock(AlarmSuppressionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AlarmSuppressionController(service)).build();
        mvc.perform(post("/api/v1/suppressions").contentType(MediaType.APPLICATION_JSON).content("""
                {"ruleId":"rule","entity":"host","origin":"MANUAL","reason":"evidence","alarmId":"alarm","windowSeconds":3600}
                """)).andExpect(status().isOk());
        verify(service).record("rule", "host", "MANUAL", "evidence", "alarm", "operator", 3600L, false);
        mvc.perform(post("/api/v1/suppressions").contentType(MediaType.APPLICATION_JSON).content("""
                {"ruleId":"rule","ruleWide":true,"origin":"MANUAL","reason":"maintenance","windowSeconds":7200}
                """)).andExpect(status().isOk());
        verify(service).record("rule", null, "MANUAL", "maintenance", null, "operator", 7200L, true);
    }

    @Test
    void invalidDurationNeverReachesTheService() throws Exception {
        var service = mock(AlarmSuppressionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AlarmSuppressionController(service)).build();
        mvc.perform(post("/api/v1/suppressions").contentType(MediaType.APPLICATION_JSON).content("""
                {"ruleId":"rule","entity":"host","origin":"MANUAL","reason":"evidence","windowSeconds":0}
                """)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void listsWindowsAndReleasesTheExactSelectedScope() throws Exception {
        var service = mock(AlarmSuppressionService.class);
        when(service.list()).thenReturn(List.of(Map.of("ruleId", "rule", "entity", "host")));
        var mvc = MockMvcBuilders.standaloneSetup(new AlarmSuppressionController(service)).build();
        mvc.perform(get("/api/v1/suppressions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].entity").value("host"));
        mvc.perform(delete("/api/v1/suppressions").param("ruleId", "rule").param("entity", "host"))
                .andExpect(status().isOk());
        verify(service).release("rule", "host");
    }
}
