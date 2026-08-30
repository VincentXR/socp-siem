package com.socp.alert.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.alert.domain.Alarm;
import com.socp.alert.service.AlarmFeedbackService;
import com.socp.alert.service.AlarmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import jakarta.validation.Validation;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AlarmFeedbackControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @Mock
    private AlarmService alarmService;
    @Mock
    private AlarmFeedbackService feedbackService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AlarmFeedbackController(alarmService, feedbackService))
                .setValidator(new SpringValidatorAdapter(
                        Validation.buildDefaultValidatorFactory().getValidator()))
                .build();
    }

    @Test
    void feedbackRequiresAnExistingTenantAlarmAndReturnsSavedData() throws Exception {
        given(alarmService.get("alarm-1")).willReturn(new Alarm());
        given(feedbackService.save(any(), any(), any(), any(), any()))
                .willReturn(Map.of("alarmId", "alarm-1", "kind", "FALSE_POSITIVE", "reason", "known scanner"));

        mvc.perform(post("/api/v1/alarms/alarm-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "kind", "FALSE_POSITIVE", "reason", "known scanner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alarmId").value("alarm-1"))
                .andExpect(jsonPath("$.data.kind").value("FALSE_POSITIVE"));
    }

    @Test
    void feedbackRejectsUnknownKind() throws Exception {
        mvc.perform(post("/api/alarms/alarm-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "UNKNOWN", "reason", "x"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void feedbackListIsWrappedInApiEnvelope() throws Exception {
        given(alarmService.get("alarm-1")).willReturn(new Alarm());
        given(feedbackService.list("alarm-1")).willReturn(List.of());
        mvc.perform(get("/api/alarms/alarm-1/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }
}
