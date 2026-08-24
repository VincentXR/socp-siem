package com.socp.report.web.service;

import com.socp.platform.client.AlertClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpService;
import com.socp.platform.error.ApiException;
import com.socp.report.web.model.ReportSummary;
import com.socp.report.web.config.ClickHouseProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ReportServiceTest {

    @Test
    void fallsBackToTypedAlertStatisticsWhenClickHouseIsUnavailable() {
        AlertClient alerts = mock(AlertClient.class);
        given(alerts.stats("today")).willReturn(new ServiceCall(
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
        assertThat(report.source()).isEqualTo("alert-web");
        assertThat(report.degraded()).isTrue();
    }

    @Test
    void failsExplicitlyWhenEveryTrendDataSourceIsUnavailable() {
        AlertClient alerts = mock(AlertClient.class);
        given(alerts.stats("7d")).willReturn(new ServiceCall(
                SocpService.ALERT, "http://alert", false, 503, "", "unavailable", 1, true, 1));
        ReportService service = serviceWithUnavailableClickHouse(alerts);

        org.assertj.core.api.Assertions.assertThatThrownBy(service::trend7d)
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(503);
    }

    private static ReportService serviceWithUnavailableClickHouse(AlertClient alerts) {
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUrl("http://127.0.0.1:1");
        properties.setUser("default");
        properties.setPassword("test");
        return new ReportService(alerts, properties);
    }
}
