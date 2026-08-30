package com.socp.alert.api.controller;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmEvidenceView;
import com.socp.alert.domain.Severity;
import com.socp.alert.api.response.AlarmEvidenceResponse;
import com.socp.alert.service.AlarmService;
import com.socp.alert.service.AlarmDispositionService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.validation.Validation;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AlarmControllerTest {

    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @Mock
    private AlarmService service;

    @Mock
    private AlarmDispositionService dispositionService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AlarmController(service),
                        new AlarmBatchDispositionController(service, dispositionService))
                .setValidator(new SpringValidatorAdapter(
                        Validation.buildDefaultValidatorFactory().getValidator()))
                .build();
    }

    @Test
    void createReturnsAlarmEnvelope() throws Exception {
        Alarm alarm = new Alarm("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed login", "203.0.113.10");
        given(service.create(any(Alarm.class), anyList())).willReturn(alarm);

        mvc.perform(post("/api/alarms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "ruleId", "AUTH-BRUTE",
                                "ruleName", "SSH brute force",
                                "severity", "HIGH",
                                "message", "failed login",
                                "entity", "203.0.113.10"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ruleId").value("AUTH-BRUTE"))
                .andExpect(jsonPath("$.data.severity").value("HIGH"));
    }

    @Test
    void createRejectsMissingRequiredRuleFields() throws Exception {
        mvc.perform(post("/api/alarms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "ruleName", "SSH brute force",
                                "severity", "HIGH"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pagedQueryPreservesResponseContract() throws Exception {
        Alarm alarm = new Alarm("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed login", "203.0.113.10");
        given(service.page(null, null, null, null, "occurredAt", "descending", 1, 20))
                .willReturn(new PageImpl<>(List.of(alarm)));

        mvc.perform(get("/api/alarms")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void evidenceEndpointReturnsCapturedEvents() throws Exception {
        given(service.evidence("alarm-1")).willReturn(new AlarmEvidenceResponse(
                "alarm-1", 1, true, "eventId=evt-1",
                List.of(new AlarmEvidenceView("evidence-1", "evt-1", java.time.Instant.parse("2026-08-18T10:00:00Z"),
                        "auth", "web-1", "HIGH", "failed login", Map.of("src_ip", "10.0.0.9"), 0))));

        mvc.perform(get("/api/alarms/alarm-1/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alarmId").value("alarm-1"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.data.items[0].raw").value("failed login"));
    }

    @Test
    void similarEndpointReturnsBoundedCandidates() throws Exception {
        given(service.similar("alarm-1", 20)).willReturn(List.of(new Alarm()));

        mvc.perform(get("/api/v1/alarms/alarm-1/similar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void batchRequiresIdempotencyKeyAndReturnsAcceptedIds() throws Exception {
        given(service.createBatch(anyList(), org.mockito.ArgumentMatchers.eq("batch-1")))
                .willReturn(Map.of("count", 1, "accepted", List.of("alarm-1"), "duplicates", List.of()));

        mvc.perform(post("/api/alarms/batch")
                        .header("Idempotency-Key", "batch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("alarms", List.of(Map.of(
                                "ruleId", "AUTH-BRUTE",
                                "ruleName", "SSH brute force",
                                "severity", "HIGH"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.accepted[0]").value("alarm-1"));
    }

    @Test
    void batchWithoutIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/alarms/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("alarms", List.of(Map.of(
                                "ruleId", "AUTH-BRUTE", "ruleName", "SSH brute force"))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchDispositionValidatesAlarmIdsAndReturnsItems() throws Exception {
        given(service.get("alarm-1")).willReturn(new Alarm());
        given(dispositionService.batchUpdate(anyList(), org.mockito.ArgumentMatchers.eq("RESOLVED"),
                org.mockito.ArgumentMatchers.eq("alice"), org.mockito.ArgumentMatchers.eq("bulk triage")))
                .willReturn(Map.of("updated", 1, "alarmIds", List.of("alarm-1"), "items", List.of()));

        mvc.perform(post("/api/v1/alarms/batch/disposition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "alarmIds", List.of("alarm-1"),
                                "status", "RESOLVED",
                                "assignee", "alice",
                                "reason", "bulk triage"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(1))
                .andExpect(jsonPath("$.data.alarmIds[0]").value("alarm-1"));
    }

    @Test
    void batchDispositionRejectsInvalidStatusBeforeServiceCall() throws Exception {
        mvc.perform(post("/api/alarms/batch/disposition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "alarmIds", List.of("alarm-1"), "status", "INVALID"))))
                .andExpect(status().isBadRequest());
    }
}
