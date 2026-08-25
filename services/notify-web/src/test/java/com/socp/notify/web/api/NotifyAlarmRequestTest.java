package com.socp.notify.web.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotifyAlarmRequestTest {

    @Test
    void mapsTypedFieldsAndConnectorSpecificAttributes() {
        NotifyAlarmRequest request = new NotifyAlarmRequest();
        request.setId("AL-1");
        request.setSeverity("HIGH");
        request.setMessage("suspicious login");
        request.addAttribute("evidence", java.util.List.of("ip:203.0.113.1"));

        assertThat(request.asMap()).containsEntry("id", "AL-1")
                .containsEntry("severity", "HIGH")
                .containsEntry("message", "suspicious login")
                .containsEntry("evidence", java.util.List.of("ip:203.0.113.1"));
    }
}
