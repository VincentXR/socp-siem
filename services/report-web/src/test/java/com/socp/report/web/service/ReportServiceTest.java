package com.socp.report.web.service;

import com.socp.platform.client.AlertClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpService;
import com.socp.report.web.model.ReportSummary;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ReportServiceTest {

    @Test
    void fallsBackToTypedAlertStatisticsWhenClickHouseIsUnavailable() {
        AlertClient alerts = mock(AlertClient.class);
        given(alerts.stats()).willReturn(new ServiceCall(
                SocpService.ALERT, "http://alert", true, 200,
                "{\"data\":{\"total\":3,\"bySeverity\":{\"HIGH\":3},\"topRules\":[{\"ruleId\":\"R-1\",\"count\":3}]}}",
                null, 1, false, 1));
        ReportService service = serviceWithUnavailableClickHouse(alerts);

        ReportSummary report = service.dailyReport();

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.bySeverity()).containsEntry("HIGH", 3);
        assertThat(report.byRule()).singleElement().satisfies(rule -> {
            assertThat(rule.rule()).isEqualTo("R-1");
            assertThat(rule.count()).isEqualTo(3);
        });
    }

    @Test
    void producesSevenEmptyDaysWhenEveryDataSourceIsEmpty() {
        AlertClient alerts = mock(AlertClient.class);
        given(alerts.stats()).willReturn(new ServiceCall(
                SocpService.ALERT, "http://alert", false, 503, "", "unavailable", 1, true, 1));
        ReportService service = serviceWithUnavailableClickHouse(alerts);

        Map<String, Object> trend = service.trend7d();

        assertThat((java.util.List<?>) trend.get("days")).hasSize(7);
        assertThat((java.util.List<?>) trend.get("counts")).allMatch(Integer.valueOf(0)::equals);
    }

    private static ReportService serviceWithUnavailableClickHouse(AlertClient alerts) {
        ReportService service = new ReportService(alerts);
        ReflectionTestUtils.setField(service, "ckUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.setField(service, "ckUser", "default");
        ReflectionTestUtils.setField(service, "ckPassword", "test");
        return service;
    }
}
