package com.socp.search.config.service;

import com.socp.platform.client.service.DetectClient;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.ThresholdRule;
import com.socp.rule.time.SourceEventTimePolicy;
import com.socp.search.config.config.IngestRuntimeProperties;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.parser.ParserRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestSourceEventTimeTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void tenant() { TenantContext.set("tenant-time"); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void quarantinedFutureEventCannotPoisonSameTenantKeyThreshold() {
        String poison = line("future", NOW.plusSeconds(86_400));
        List<String> normal = List.of(line("normal-1", NOW), line("normal-2", NOW.plusSeconds(1)),
                line("normal-3", NOW.plusSeconds(2)));
        // Reproduce the failure with the guard explicitly disabled: one legitimate
        // source clock error suppresses the whole subsequent same-key burst.
        var unguarded = normalizer(new SourceEventTimePolicy(false, Duration.ZERO));
        var poisonedRule = threshold();
        poisonedRule.accept(security(unguarded.normalize(poison, "collector").event()));
        normal.forEach(raw -> poisonedRule.accept(security(unguarded.normalize(raw, "collector").event())));
        assertThat(poisonedRule.drain()).isEmpty();

        var commits = mock(IngestionCommitService.class);
        List<SearchEvent> accepted = new ArrayList<>();
        when(commits.commit(anyList())).thenAnswer(invocation -> {
            List<SearchEvent> events = invocation.getArgument(0);
            accepted.addAll(events);
            return new IngestionCommitService.CommitResult(events.size(), events.size(), 0, 0);
        });
        var failures = mock(IngestParseFailureService.class);
        var pipeline = pipeline(normalizer(SourceEventTimePolicy.defaults()), commits, failures);

        var response = pipeline.process(poison + "\n" + String.join("\n", normal), "collector", "batch-1");

        assertThat(response).containsEntry("accepted", 3).containsEntry("parseFailed", 1)
                .containsEntry("quarantined", 1).containsEntry("acknowledged", 4);
        verify(failures).record(eq(poison), eq("collector"), eq("builtin"),
                eq("event timestamp exceeds maximum future clock skew of PT30S"), anyString());
        assertThat(accepted).extracting(SearchEvent::eventId)
                .containsExactly("normal-1", "normal-2", "normal-3");
        var protectedRule = threshold();
        accepted.forEach(event -> protectedRule.accept(security(event)));
        var alerts = protectedRule.drain();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().evidence()).extracting(SecurityEvent::id)
                .containsExactly("normal-1", "normal-2", "normal-3");
    }

    @Test
    void widenedAllowanceReintroducesShortWindowPoisoningWhileDefaultProtectsIt() {
        String poison = line("future-within-widened-allowance", NOW.plusSeconds(120));
        List<String> normal = List.of(line("normal-1", NOW), line("normal-2", NOW.plusSeconds(1)),
                line("normal-3", NOW.plusSeconds(2)));
        var widenedNormalizer = normalizer(new SourceEventTimePolicy(true, Duration.ofMinutes(5)));
        var admittedFuture = widenedNormalizer.normalize(poison, "collector").event();
        assertThat(admittedFuture.timestamp()).isEqualTo(NOW.plusSeconds(120));
        var poisonedRule = threshold();
        poisonedRule.accept(security(admittedFuture));
        normal.forEach(raw -> poisonedRule.accept(security(widenedNormalizer.normalize(raw, "collector").event())));
        // An explicitly widened five-minute allowance exceeds this rule
        // window/lateness budget and reintroduces the same-key miss.
        assertThat(poisonedRule.drain()).isEmpty();

        var commits = mock(IngestionCommitService.class);
        List<SearchEvent> accepted = new ArrayList<>();
        when(commits.commit(anyList())).thenAnswer(invocation -> {
            List<SearchEvent> events = invocation.getArgument(0);
            accepted.addAll(events);
            return new IngestionCommitService.CommitResult(events.size(), events.size(), 0, 0);
        });
        var failures = mock(IngestParseFailureService.class);
        var guardedByDefault = normalizer(SourceEventTimePolicy.defaults());
        var response = pipeline(guardedByDefault, commits, failures)
                .process(poison + "\n" + String.join("\n", normal), "collector", "short-window-batch");

        assertThat(response).containsEntry("accepted", 3).containsEntry("parseFailed", 1)
                .containsEntry("quarantined", 1).containsEntry("acknowledged", 4);
        verify(failures).record(eq(poison), eq("collector"), eq("builtin"),
                eq("event timestamp exceeds maximum future clock skew of PT30S"), anyString());
        assertThat(accepted).extracting(SearchEvent::eventId)
                .containsExactly("normal-1", "normal-2", "normal-3");
        var protectedRule = threshold();
        accepted.forEach(event -> protectedRule.accept(security(event)));
        var alerts = protectedRule.drain();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().evidence()).extracting(SecurityEvent::id)
                .containsExactly("normal-1", "normal-2", "normal-3");
    }

    @Test
    void futureRejectionCannotBeAcknowledgedIfDurableQuarantineFails() {
        var failures = mock(IngestParseFailureService.class);
        doThrow(new IllegalStateException("quarantine unavailable"))
                .when(failures).record(anyString(), anyString(), anyString(), anyString(), any());
        var pipeline = pipeline(normalizer(SourceEventTimePolicy.defaults()),
                mock(IngestionCommitService.class), failures);
        var failure = assertThrows(ApiException.class,
                () -> pipeline.process(line("future", NOW.plusSeconds(86_400)), "collector"));
        assertThat(failure.getCode()).isEqualTo(503);
    }

    @Test
    void historicalOutOfOrderEventsRetainTimestampIdentityAndSnapshotReplay() {
        var normalizer = normalizer(SourceEventTimePolicy.defaults());
        Instant historical = Instant.parse("2020-01-01T00:00:00Z");
        List<SecurityEvent> events = List.of(0, 2, 1).stream()
                .map(offset -> normalizer.normalize(line("history-" + offset,
                        historical.plusSeconds(offset)), "collector").event())
                .map(IngestSourceEventTimeTest::security).toList();
        var live = threshold();
        live.accept(events.get(0));
        live.accept(events.get(1));
        var recovered = threshold();
        recovered.restoreState(live.snapshotState());
        live.accept(events.get(2));
        recovered.accept(events.get(2));
        var expected = live.drain().getFirst();
        var replayed = recovered.drain().getFirst();
        assertThat(replayed.id()).isEqualTo(expected.id());
        assertThat(replayed.evidence()).extracting(SecurityEvent::timestamp)
                .containsExactly(historical, historical.plusSeconds(2), historical.plusSeconds(1));
        assertThat(replayed.evidence()).allSatisfy(event ->
                assertThat(event.tenantId()).isEqualTo("tenant-time"));
    }

    @Test
    void futureCheckUsesTrustedReceiptAfterConfiguredTimezoneParsing() {
        var resolver = mock(IngestSourceResolver.class);
        when(resolver.resolve(anyString(), anyString())).thenReturn(new IngestSourceContext(
                "collector", "source", ParseFormat.JSON, List.of(), "occurred_local", "Asia/Shanghai", true));
        var normalizer = new IngestEventNormalizer(null, new ParserRegistry(), resolver, null,
                SourceEventTimePolicy.defaults(), CLOCK);
        assertThrows(IngestParseException.class, () -> normalizer.normalize(
                "{\"occurred_local\":\"2026-10-03 20:00:00\","
                        + "\"ingested_at\":\"2026-10-03T12:00:00Z\",\"timestamp\":\"2020-01-01T00:00:00Z\"}",
                "collector"));
    }

    @Test
    void ingressConfigurationOverridesAndDisableAreExplicit() {
        String future = line("future", Instant.now().plusSeconds(86_400));
        var extended = new IngestEventNormalizer(null, new ParserRegistry(), null, null, true, "2d");
        var disabled = new IngestEventNormalizer(null, new ParserRegistry(), null, null, false, "0s");
        assertThat(extended.normalize(future, "collector").event().timestamp())
                .isEqualTo(disabled.normalize(future, "collector").event().timestamp());
        assertThrows(IllegalArgumentException.class,
                () -> new IngestEventNormalizer(null, new ParserRegistry(), null, null, true, "-1s"));
        assertThrows(IllegalArgumentException.class,
                () -> new IngestEventNormalizer(null, new ParserRegistry(), null, null, false, "invalid"));
    }

    @Test
    void acceptedAndGeneratedTimeUseOneReceiptWithoutClippingOrIdentityChanges() {
        var normalizer = normalizer(SourceEventTimePolicy.defaults());
        var edge = normalizer.normalize(line("edge", NOW.plusSeconds(30)), "collector");
        assertThat(edge.event().timestamp()).isEqualTo(NOW.plusSeconds(30));
        assertThat(edge.payload()).containsEntry("timestamp", NOW.plusSeconds(30).toString());
        assertThat(edge.event().fields()).containsEntry("ingested_at", NOW.toString());
        var generated = normalizer.normalize("{\"eventId\":\"generated\",\"source\":\"auth\"}", "collector");
        assertThat(generated.event().timestamp()).isEqualTo(NOW);
        assertThat(generated.event().fields()).containsEntry("ingested_at", NOW.toString())
                .containsEntry("event_time_generated", "true");
        var later = new IngestEventNormalizer(null, new ParserRegistry(), null, null,
                SourceEventTimePolicy.defaults(), Clock.offset(CLOCK, Duration.ofDays(1)))
                .normalize(line("edge", NOW.plusSeconds(30)), "collector");
        assertThat(IngestionEventIdentity.fingerprint(later.event()))
                .isEqualTo(IngestionEventIdentity.fingerprint(edge.event()));
    }

    private static IngestEventNormalizer normalizer(SourceEventTimePolicy policy) {
        return new IngestEventNormalizer(null, new ParserRegistry(), null, null, policy, CLOCK);
    }

    private static IngestPipeline pipeline(IngestEventNormalizer normalizer,
                                           IngestionCommitService commits, IngestParseFailureService failures) {
        var monitor = mock(IngestTaskMonitor.class);
        when(monitor.runtime("collector", true)).thenReturn(Map.of("eps1m", 0.0));
        return new IngestPipeline(normalizer, commits, monitor, mock(DetectClient.class),
                new SimpleMeterRegistry(), new IngestRuntimeProperties(), failures);
    }

    private static ThresholdRule threshold() {
        return new ThresholdRule("clock-threshold", "Clock threshold", event -> true,
                event -> event.tenantId() + "|" + event.get("src_ip"), 3, Duration.ofMinutes(1),
                Severity.HIGH, "{key}");
    }

    private static SecurityEvent security(SearchEvent event) {
        return new SecurityEvent(event.eventId(), event.timestamp(), event.source(), event.host(),
                event.msg(), event.fields(), Severity.valueOf(event.severity()));
    }

    private static String line(String id, Instant timestamp) {
        return "{\"eventId\":\"" + id + "\",\"source\":\"auth\",\"host\":\"host-1\","
                + "\"timestamp\":\"" + timestamp + "\",\"src_ip\":\"203.0.113.10\",\"msg\":\"login failed\"}";
    }
}
