package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.time.SourceEventTimePolicy;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.parser.ParserRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SyslogEventTimeTest {
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @ParameterizedTest
    @CsvSource({
            "2026-01-01T00:00:10Z,UTC,Dec 31 23:59:59,2025-12-31T23:59:59Z",
            "2025-12-31T23:59:59Z,UTC,Jan  1 00:00:01,2026-01-01T00:00:01Z",
            "2026-10-30T00:00:00Z,Asia/Shanghai,Oct 11 22:14:15,2026-10-11T14:14:15Z",
            "2024-03-01T00:00:00Z,UTC,Feb 29 23:59:59,2024-02-29T23:59:59Z"
    })
    void yearAndTimezoneUseReceiptContextWithoutFallingBackToReceipt(String receipt, String zone,
                                                                   String timestamp, String expected) {
        TenantContext.set("syslog-time");
        var event = normalizer(receipt, zone).normalize("<165>" + timestamp + " host app[123]: delayed log", "collector").event();
        assertThat(event.timestamp()).isEqualTo(Instant.parse(expected));
        assertThat(event.severity()).isEqualTo("MEDIUM");
        assertThat(event.fields()).doesNotContainKey("event_time_generated");
    }

    @Test
    void plainAuthLogRetainsItsYearlessTimeThroughTheAutomaticRouter() {
        TenantContext.set("syslog-time");
        var normalizer = new IngestEventNormalizer(null, new ParserRegistry(), null, null,
                SourceEventTimePolicy.defaults(), Clock.fixed(Instant.parse("2026-01-01T00:00:10Z"), ZoneOffset.UTC));
        var event = normalizer.normalize("Dec 31 23:59:59 host sshd[123]: Failed password for root from 192.0.2.1", "vector").event();
        assertThat(event.timestamp()).isEqualTo(Instant.parse("2025-12-31T23:59:59Z"));
        assertThat(event.fields()).containsEntry("action", "login_failed").doesNotContainKey("event_time_generated");
    }

    @Test
    void invalidCalendarAndFutureClockRemainQuarantinable() {
        TenantContext.set("syslog-time");
        var normalizer = normalizer("2026-10-30T00:00:00Z", "UTC");
        for (String time : List.of("Feb 30 12:00:00", "Oct 30 01:00:00")) {
            assertThatThrownBy(() -> normalizer.normalize("<165>" + time + " host app: invalid", "collector"))
                    .isInstanceOf(IngestParseException.class);
        }
    }

    private static IngestEventNormalizer normalizer(String receipt, String zone) {
        var resolver = mock(IngestSourceResolver.class);
        when(resolver.resolve(anyString(), anyString())).thenReturn(new IngestSourceContext(
                "collector", "syslog-source", ParseFormat.SYSLOG, List.of(), "timestamp", zone, true));
        return new IngestEventNormalizer(null, new ParserRegistry(), resolver, null, SourceEventTimePolicy.defaults(),
                Clock.fixed(Instant.parse(receipt), ZoneOffset.UTC));
    }
}
