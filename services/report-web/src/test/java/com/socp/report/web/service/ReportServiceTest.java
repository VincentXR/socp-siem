package com.socp.report.web.service;

import com.sun.net.httpserver.HttpServer;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.error.exception.ApiException;
import com.socp.report.web.domain.ReportSummary;
import com.socp.report.web.domain.ReportTrend;
import com.socp.report.web.config.ClickHouseProperties;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ReportServiceTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

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

    @Test
    void usesClickHouseRowsForDailyReportAndRecordsFreshness() throws Exception {
        AlertClient alerts = mock(AlertClient.class);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String sql = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body;
            if (sql.contains("GROUP BY severity")) {
                body = "HIGH\t2\nLOW\t1\n";
            } else if (sql.contains("GROUP BY rule_id")) {
                body = "R-1\tBrute Force\t2\n";
            } else {
                body = "1710000000000\n";
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            ClickHouseProperties properties = clickHouseProperties(server);
            ReportSummary report = new ReportService(alerts, properties).dailyReport();

            assertThat(report.total()).isEqualTo(3);
            assertThat(report.bySeverity()).containsEntry("HIGH", 2).containsEntry("LOW", 1);
            assertThat(report.byRule()).singleElement().satisfies(rule -> {
                assertThat(rule.rule()).isEqualTo("R-1 Brute Force");
                assertThat(rule.count()).isEqualTo(2);
            });
            assertThat(report.source()).isEqualTo("clickhouse");
            assertThat(report.degraded()).isFalse();
            assertThat(report.freshness()).isEqualTo(java.time.Instant.ofEpochMilli(1710000000000L));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackOnlyTopRulesWhenClickHouseRuleQueryFails() throws Exception {
        AlertClient alerts = mock(AlertClient.class);
        given(alerts.stats("today")).willReturn(new ServiceCall(
                SocpService.ALERT, "http://alert", true, 200,
                "{\"data\":{\"total\":2,\"bySeverity\":{\"HIGH\":2},"
                        + "\"topRules\":[{\"ruleId\":\"R-9\",\"count\":2}]}}",
                null, 1, false, 1));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String sql = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (sql.contains("GROUP BY rule_id")) {
                exchange.sendResponseHeaders(503, 0);
                exchange.close();
                return;
            }
            String body = sql.contains("GROUP BY severity") ? "HIGH\t2\n" : "1710000000000\n";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            ReportSummary report = new ReportService(alerts, clickHouseProperties(server)).dailyReport();

            assertThat(report.total()).isEqualTo(2);
            assertThat(report.source()).isEqualTo("clickhouse+alert-web");
            assertThat(report.degraded()).isTrue();
            assertThat(report.degradationReason()).contains("top rules came from alert-web");
            assertThat(report.byRule()).singleElement().extracting(ReportSummary.RuleCount::rule)
                    .isEqualTo("R-9");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesClickHouseTrendRowsAndFillsMissingDays() throws Exception {
        AlertClient alerts = mock(AlertClient.class);
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String sql = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body = sql.contains("toDate(ts)")
                    ? today.minusDays(2) + "\t4\nbad-row\n" + today + "\t2\n"
                    : "1710000000000\n";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            ReportTrend trend = new ReportService(alerts, clickHouseProperties(server)).trend7d();

            assertThat(trend.source()).isEqualTo("clickhouse");
            assertThat(trend.degraded()).isFalse();
            assertThat(trend.days()).hasSize(7);
            assertThat(trend.counts()).containsExactly(0, 0, 0, 0, 4, 0, 2);
            assertThat(trend.freshness()).isEqualTo(java.time.Instant.ofEpochMilli(1710000000000L));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToAlertWebTrendWhenClickHouseIsUnavailable() {
        AlertClient alerts = mock(AlertClient.class);
        given(alerts.stats("7d")).willReturn(new ServiceCall(
                SocpService.ALERT, "http://alert", true, 200,
                "{\"data\":{\"total\":3,\"trend7d\":{\"2026-08-29\":3}}}",
                null, 1, false, 1));
        ReportTrend trend = new ReportService(alerts, serviceProperties("http://127.0.0.1:1")).trend7d();

        assertThat(trend.source()).isEqualTo("alert-web");
        assertThat(trend.degraded()).isTrue();
        assertThat(trend.degradationReason()).contains("ClickHouse unavailable");
        assertThat(trend.counts()).hasSize(7);
    }

    private static ReportService serviceWithUnavailableClickHouse(AlertClient alerts) {
        return new ReportService(alerts, serviceProperties("http://127.0.0.1:1"));
    }

    private static ClickHouseProperties serviceProperties(String url) {
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUrl(url);
        properties.setUser("default");
        properties.setPassword("test");
        return properties;
    }

    private static ClickHouseProperties clickHouseProperties(HttpServer server) {
        ClickHouseProperties properties = serviceProperties(
                "http://127.0.0.1:" + server.getAddress().getPort());
        properties.setEnabled(true);
        return properties;
    }
}
