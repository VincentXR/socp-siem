package com.socp.rule;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.socp.rule.config.Rules;
import com.socp.rule.engine.AlertSink;
import com.socp.rule.engine.DetectionResult;
import com.socp.rule.engine.EventAlertSink;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleExecutionScope;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.engine.Suppressor;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.PatternRule;
import com.socp.rule.rules.Rule;
import com.socp.rule.rules.ThresholdRule;
import com.socp.rule.state.StatefulRule;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎单测（com.siem 迁移验证）：模式/阈值/关联规则 + 抑制去重。
 * 引擎为异步单消费者模型，断言用带截止时间的轮询等待。
 */
class RuleEngineTest {

    /**
     * 采集 RuleEngine 自己的日志。用于验证「异常在就地消化」这一契约：
     * 引擎不得把观测器/清理阶段的失败升级成事件失败，但必须留下可下钻的诊断行。
     */
    private static final class EngineLogs implements AutoCloseable {
        private final Logger logger;
        private final Level originalLevel;
        private final ListAppender<ILoggingEvent> events = new ListAppender<>();

        private EngineLogs() {
            logger = (Logger) LoggerFactory.getLogger(RuleEngine.class);
            originalLevel = logger.getLevel();
            logger.setLevel(Level.DEBUG);
            events.start();
            logger.addAppender(events);
        }

        private List<String> messages(Level level) {
            return events.list.stream()
                    .filter(event -> event.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(events);
            logger.setLevel(originalLevel);
            events.stop();
        }
    }

    static final class CollectingSink implements AlertSink {
        final List<Alert> alerts = new CopyOnWriteArrayList<>();

        @Override
        public void publish(Alert alert) {
            alerts.add(alert);
        }

        @Override
        public void close() {
        }
    }

    private static SecurityEvent ev(String source, String msg, String srcIp, String action) {
        return new SecurityEvent(Instant.now(), source, "host1", msg,
                Map.of("msg", msg, "src_ip", srcIp == null ? "0.0.0.0" : srcIp,
                        "action", action == null ? "" : action), Severity.INFO);
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(cond.getAsBoolean(), "等待断言条件超时");
    }

    @Test
    void failedDurableDeliveryDoesNotSuppressRetry() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        List<Alert> delivered = new CopyOnWriteArrayList<>();
        EventAlertSink sink = new EventAlertSink() {
            @Override public void publish(SecurityEvent event, List<Alert> alerts) {
                if (fail.getAndSet(false)) throw new IllegalStateException("database unavailable");
                delivered.addAll(alerts);
            }
            @Override public void publish(Alert alert) { delivered.add(alert); }
            @Override public void close() { }
        };
        try (Suppressor suppressor = new Suppressor(Duration.ofMinutes(5));
             RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(sink), suppressor)) {
            engine.start();
            SecurityEvent event = ev("web", "GET /x?q=1' OR '1'='1 (SQLi)", "10.0.0.5", null);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS));
            engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS);
            assertEquals(1, delivered.stream().filter(alert -> alert.ruleId().equals("WEB-ATTACK")).count());
            engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS);
            assertEquals(1, delivered.stream().filter(alert -> alert.ruleId().equals("WEB-ATTACK")).count());
        }
    }

    @Test
    void patternRuleFiresOnWebAttack() throws Exception {
        CollectingSink sink = new CollectingSink();
        try (RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(sink))) {
            engine.start();
            engine.ingest(ev("web", "GET /x?q=1' OR '1'='1 (SQLi)", "10.0.0.5", null));

            await(() -> sink.alerts.stream().anyMatch(a -> a.ruleId().equals("WEB-ATTACK")));
            assertTrue(sink.alerts.stream().anyMatch(a -> a.ruleId().equals("WEB-ATTACK")), "Web 攻击应告警");
        }
    }

    @Test
    void thresholdRuleFiresAfterFiveFailuresAndClearsBucket() throws Exception {
        CollectingSink sink = new CollectingSink();
        try (RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(sink))) {
            engine.start();
            for (int i = 0; i < 5; i++) {
                engine.ingest(ev("auth", "Failed password for admin from 10.0.0.9", "10.0.0.9", null));
            }

            await(() -> sink.alerts.stream().anyMatch(a -> a.ruleId().equals("AUTH-BRUTE")));

            long bruteAlerts = sink.alerts.stream().filter(a -> a.ruleId().equals("AUTH-BRUTE")).count();
            assertEquals(1, bruteAlerts, "5 次失败登录应恰好告警一次（桶已清空，重新计数）");

            // 桶清空后：再灌 4 次不应立刻重复告警
            for (int i = 0; i < 4; i++) {
                engine.ingest(ev("auth", "Failed password for admin from 10.0.0.9", "10.0.0.9", null));
            }
            Thread.sleep(200);
            assertEquals(1, sink.alerts.stream().filter(a -> a.ruleId().equals("AUTH-BRUTE")).count(),
                    "4 次不足阈值，不应重复告警");
        }
    }

    @Test
    void restoreRebuildsThresholdWindowWithoutReplayingHistoricalAlert() throws Exception {
        CollectingSink sink = new CollectingSink();
        List<SecurityEvent> history = List.of(
                ev("auth", "Failed password for admin from 10.0.0.10", "10.0.0.10", null),
                ev("auth", "Failed password for admin from 10.0.0.10", "10.0.0.10", null),
                ev("auth", "Failed password for admin from 10.0.0.10", "10.0.0.10", null),
                ev("auth", "Failed password for admin from 10.0.0.10", "10.0.0.10", null));
        try (RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(sink))) {
            engine.restore(history);
            assertTrue(sink.alerts.isEmpty(), "状态恢复不应重新发送历史告警");
            engine.start();
            engine.ingest(ev("auth", "Failed password for admin from 10.0.0.10", "10.0.0.10", null));

            await(() -> sink.alerts.stream().anyMatch(a -> a.ruleId().equals("AUTH-BRUTE")));
            assertEquals(1, sink.alerts.stream().filter(a -> a.ruleId().equals("AUTH-BRUTE")).count());
        }
    }

    @Test
    void correlationRuleFiresOnFailedThenAccepted() throws Exception {
        CollectingSink sink = new CollectingSink();
        try (RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(sink))) {
            engine.start();
            engine.ingest(ev("auth", "Failed password for admin from 10.0.0.7", "10.0.0.7", null));
            engine.ingest(ev("auth", "Accepted password for admin from 10.0.0.7", "10.0.0.7", null));

            await(() -> sink.alerts.stream().anyMatch(a -> a.ruleId().equals("AUTH-BRUTE-SUCCESS")));
            assertTrue(sink.alerts.stream().anyMatch(a -> a.ruleId().equals("AUTH-BRUTE-SUCCESS")),
                    "失败→成功 事件链应触发关联告警");
        }
    }

    @Test
    void suppressorDeduplicatesSameRuleAndEntity() throws Exception {
        CollectingSink sink = new CollectingSink();
        Suppressor suppressor = new Suppressor(Duration.ofMinutes(5));
        try (RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(sink), suppressor)) {
            engine.start();
            // 同一 src_ip 连续两次 Web 攻击：第一次放行，窗口内第二次被抑制
            engine.ingest(ev("web", "SQLi attempt", "10.0.0.8", null));
            await(() -> sink.alerts.stream().anyMatch(a -> a.ruleId().equals("WEB-ATTACK")));
            engine.ingest(ev("web", "SQLi attempt again", "10.0.0.8", null));
            Thread.sleep(200);

            assertEquals(1, sink.alerts.stream().filter(a -> a.ruleId().equals("WEB-ATTACK")).count(),
                    "抑制窗口内同一实体重复告警应被去重");
            assertTrue(engine.suppressedCount() >= 1, "应有被抑制计数");
        } finally {
            suppressor.close();
        }
    }

    @Test
    void durableCompletionPropagatesSinkFailureToTheCaller() throws Exception {
        AlertSink failing = new AlertSink() {
            @Override
            public void publish(Alert alert) {
                throw new IllegalStateException("outbox unavailable");
            }

            @Override
            public void close() {
            }
        };
        try (RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(failing))) {
            engine.start();
            var completion = engine.ingestAndAwait(
                    ev("web", "SQLi attempt", "10.0.0.99", null));
            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> completion.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void failedDurableDeliveryRollsBackStateBeforeRetry() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        List<Alert> delivered = new CopyOnWriteArrayList<>();
        EventAlertSink sink = new EventAlertSink() {
            @Override
            public void publish(SecurityEvent event, List<Alert> alerts) {
                if (fail.getAndSet(false)) throw new IllegalStateException("outbox unavailable");
                delivered.addAll(alerts);
            }

            @Override public void close() { }
        };
        ThresholdRule rule = new ThresholdRule("ROLLBACK", "rollback", event -> true,
                SecurityEvent::host, 2, Duration.ofMinutes(5), Severity.HIGH, "threshold");
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink))) {
            engine.start();
            SecurityEvent event = ev("auth", "failure", "rollback-host", null);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS));

            // The retried event must be the first item in the window again;
            // otherwise the next event would fail to produce the threshold.
            engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS);
            engine.ingestAndAwait(ev("auth", "failure-2", "rollback-host", null))
                    .get(3, TimeUnit.SECONDS);
            assertEquals(1, delivered.size());
            assertEquals(2, delivered.getFirst().evidence().size());
        }
    }

    @Test
    void rolledBackEventDoesNotLeakItsCandidateAlertIntoTheNextEvent() throws Exception {
        CollectingSink sink = new CollectingSink();
        ThresholdRule emitter = new ThresholdRule("LEAK-CHECK", "leak", ignored -> true,
                SecurityEvent::host, 1, Duration.ofMinutes(5), Severity.HIGH, "threshold");
        Rule transientFailure = new Rule() {
            private final AtomicInteger invocations = new AtomicInteger();

            @Override public String id() { return "TRANSIENT"; }
            @Override public String name() { return "transient"; }
            @Override public void accept(SecurityEvent event) {
                // One transient failure, then the dependency is back: the point is
                // what the rolled-back event leaves behind, not a fused rule.
                if (invocations.incrementAndGet() == 1) {
                    throw new IllegalStateException("dependency unavailable");
                }
            }
            @Override public List<Alert> drain() { return List.of(); }
        };
        try (RuleEngine engine = new RuleEngine(List.of(emitter, transientFailure), List.of(sink))) {
            engine.start();
            SecurityEvent failed = ev("auth", "failure", "leak-host", null);
            // The candidate alert the first rule emitted before the second rule
            // failed is not part of the serialized state, so the whole-event
            // rollback has to discard it as well.
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.ingestAndAwait(failed).get(3, TimeUnit.SECONDS));
            assertTrue(sink.alerts.isEmpty(), "回滚的事件不得投递告警");

            SecurityEvent next = ev("auth", "next", "next-host", null);
            engine.ingestAndAwait(next).get(3, TimeUnit.SECONDS);
            assertEquals(1, sink.alerts.size(), "上一事件的候选告警不得随本事件投递");
            Alert delivered = sink.alerts.getFirst();
            assertEquals(1, delivered.evidence().size());
            assertEquals(next.id(), delivered.evidence().getFirst().id(),
                    "投递出的告警必须属于它被 drain 时的那个事件");
        }
    }

    @Test
    void suppressedCountIsAttributedToTheEngineThatMadeTheDecision() throws Exception {
        CollectingSink sink = new CollectingSink();
        Suppressor suppressor = new Suppressor(Duration.ofMinutes(5));
        try (RuleEngine decided = new RuleEngine(Rules.defaultRules(), List.of(sink), suppressor);
             RuleEngine idle = new RuleEngine(Rules.defaultRules(), List.of(sink), suppressor)) {
            decided.start();
            idle.start();
            decided.ingest(ev("web", "SQLi attempt", "10.0.0.8", null));
            await(() -> sink.alerts.stream().anyMatch(a -> a.ruleId().equals("WEB-ATTACK")));
            decided.ingest(ev("web", "SQLi attempt again", "10.0.0.8", null));
            Thread.sleep(200);

            assertTrue(decided.suppressedCount() >= 1, "做过抑制决策的引擎必须计数");
            assertEquals(0, idle.suppressedCount(),
                    "共享 Suppressor 的进程级总数不得记到没做该决策的引擎上");
        } finally {
            suppressor.close();
        }
    }

    @Test
    void routingMismatchIsReportedOncePerWindowForTheDeclaredGrouping() throws Exception {
        List<String> reported = new CopyOnWriteArrayList<>();
        RuleProcessingObserver observer = new RuleProcessingObserver() {
            @Override public void routingMismatched(SecurityEvent event, String ruleId,
                                                    String declaredField, String eventRoutingField) {
                reported.add(ruleId + "|" + declaredField + "|" + eventRoutingField);
            }
        };
        ThresholdRule rule = new ThresholdRule("USER-GROUP", "user group", ignored -> false,
                SecurityEvent::host, 2, Duration.ofMinutes(5), Severity.HIGH, "threshold");
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(), null, observer,
                RuleExecutionScope.NOOP, null, Map.of(),
                Map.of("USER-GROUP", new RuleEngine.RoutingDimension("user", 3600)))) {
            engine.start();
            engine.ingestAndAwait(ev("auth", "one", "10.0.0.1", null)).get(3, TimeUnit.SECONDS);
            engine.ingestAndAwait(ev("auth", "two", "10.0.0.2", null)).get(3, TimeUnit.SECONDS);

            assertEquals(1, reported.size(), "每规则每窗口最多报告一次");
            assertEquals("USER-GROUP|user|src_ip", reported.getFirst());
            assertEquals(1, engine.routingMismatchWindows());
            Map<String, Object> stats = engine.ruleStats().stream()
                    .filter(item -> "USER-GROUP".equals(item.get("id"))).findFirst().orElseThrow();
            assertEquals("user", stats.get("routingField"));
            assertEquals(1L, stats.get("routingMismatchWindows"));
        }
    }

    @Test
    void awaitIdleWaitsForAcceptedWorkAndKeepsTheEngineServing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EventAlertSink blocking = new EventAlertSink() {
            @Override public void publish(SecurityEvent event, List<Alert> alerts) {
                entered.countDown();
                try {
                    assertTrue(release.await(3, TimeUnit.SECONDS), "测试未放行阻塞的 sink");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }

            @Override public void close() { }
        };
        ThresholdRule rule = new ThresholdRule("IDLE", "idle", ignored -> true,
                SecurityEvent::host, 1, Duration.ofMinutes(5), Severity.HIGH, "threshold");
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(blocking))) {
            engine.start();
            engine.ingest(ev("auth", "in flight", "idle-host", null));
            assertTrue(entered.await(2, TimeUnit.SECONDS), "sink 必须已进入");
            assertFalse(engine.awaitIdle(150), "仍有在途工作时不得报告空闲");

            release.countDown();
            assertTrue(engine.awaitIdle(3000), "已接受的工作完成后必须报告空闲");
            engine.ingestAndAwait(ev("auth", "after", "idle-host-2", null)).get(3, TimeUnit.SECONDS);
            assertTrue(engine.eventCount() >= 2, "awaitIdle 不得像 close 那样终止引擎");
            assertTrue(engine.awaitIdle(1000), "空闲后再次等待仍成立");
        }
    }

    @Test
    void permanentlyBadRuleIsFusedWithoutBlockingHealthyRules() throws Exception {
        AtomicInteger badInvocations = new AtomicInteger();
        Rule bad = new Rule() {
            @Override public String id() { return "BAD-RULE"; }
            @Override public String name() { return "bad"; }
            @Override public void accept(SecurityEvent event) {
                badInvocations.incrementAndGet();
                throw new IllegalArgumentException("invalid rule value");
            }
            @Override public List<Alert> drain() { return List.of(); }
        };
        PatternRule healthy = new PatternRule("HEALTHY", "healthy", ignored -> true,
                Severity.INFO, "healthy", "healthy");
        CollectingSink sink = new CollectingSink();
        try (RuleEngine engine = new RuleEngine(List.of(bad, healthy), List.of(sink))) {
            engine.start();
            engine.ingestAndAwait(ev("system", "first", "bad-rule-host", null))
                    .get(3, TimeUnit.SECONDS);
            engine.ingestAndAwait(ev("system", "second", "bad-rule-host", null))
                    .get(3, TimeUnit.SECONDS);

            assertEquals(2, sink.alerts.size(), "healthy rules must continue after bad-rule isolation");
            assertEquals(1, badInvocations.get(), "an isolated rule is not retried for every event");
            Map<String, Object> stats = engine.ruleStats().stream()
                    .filter(item -> "BAD-RULE".equals(item.get("id"))).findFirst().orElseThrow();
            assertEquals("OPEN", stats.get("ruleCircuit"));
            assertEquals(1L, stats.get("ruleFailures"));
        }
    }

    @Test
    void durableCommitGuardRunsBeforeAStaleWorkerCanPublish() throws Exception {
        AtomicBoolean fenced = new AtomicBoolean(true);
        List<Alert> delivered = new CopyOnWriteArrayList<>();
        AlertSink sink = new AlertSink() {
            @Override public void publish(Alert alert) { delivered.add(alert); }
            @Override public void close() { }
        };
        try (RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(sink), null,
                null, null, () -> {
                    if (fenced.getAndSet(false)) throw new IllegalStateException("stale owner");
                })) {
            engine.start();
            SecurityEvent event = ev("web", "SQLi attempt", "10.0.0.200", null);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS));
            assertTrue(delivered.isEmpty());
            engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS);
            assertTrue(delivered.stream().anyMatch(alert -> alert.ruleId().equals("WEB-ATTACK")));
        }
    }

    @Test
    void durableCompletionIncludesZeroAlertEvents() throws Exception {
        List<List<Alert>> results = new CopyOnWriteArrayList<>();
        EventAlertSink sink = new EventAlertSink() {
            @Override
            public void publish(SecurityEvent event, List<Alert> alerts) {
                results.add(alerts);
            }

            @Override
            public void close() {
            }
        };
        try (RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(sink))) {
            engine.start();
            engine.ingestAndAwait(ev("system", "heartbeat", "10.0.0.100", null))
                    .get(3, TimeUnit.SECONDS);
            assertEquals(1, results.size());
            assertTrue(results.get(0).isEmpty());
        }
    }

    @Test
    void eventAwareSinkReceivesPositionVersionsStateAndSuppressionDecision() throws Exception {
        java.util.concurrent.atomic.AtomicReference<DetectionResult> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        EventAlertSink sink = new EventAlertSink() {
            @Override
            public void publish(SecurityEvent event, List<Alert> alerts) {
                throw new AssertionError("the explicit result overload should be used");
            }

            @Override
            public void publish(DetectionResult result, Runnable durableCommitGuard) {
                captured.set(result);
            }

            @Override
            public void close() {
            }
        };
        ThresholdRule rule = new ThresholdRule("RESULT-RULE", "result", ignored -> true,
                SecurityEvent::host, 1, Duration.ofMinutes(5), Severity.HIGH, "result");
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink), null,
                null, RuleExecutionScope.NOOP, null, Map.of("RESULT-RULE", "v2"))) {
            engine.start();
            SecurityEvent event = ev("auth", "result-event", "result-host", null);
            engine.ingestAndAwait(event,
                    new DetectionResult.InputPosition("socp-events", 3, 17L), null, null)
                    .get(3, TimeUnit.SECONDS);

            DetectionResult result = captured.get();
            assertEquals(event.id(), result.event().id());
            assertEquals(new DetectionResult.InputPosition("socp-events", 3, 17L),
                    result.inputPosition());
            assertEquals("v2", result.ruleVersions().get("RESULT-RULE"));
            assertEquals(1, result.candidates().size());
            assertEquals(1, result.alerts().size());
            assertEquals("NONE", result.suppression().policy());
            assertEquals(1, result.stateChanges().size());
            assertTrue(result.stateChanges().getFirst().changed());
            assertEquals(event.scopedId(), result.idempotencyKey());
        }
    }

    @Test
    void durableCallbackRunsBeforeCompletionSignal() throws Exception {
        AtomicBoolean callbackRan = new AtomicBoolean();
        try (RuleEngine engine = new RuleEngine(List.of(), List.of())) {
            engine.start();
            engine.ingestAndAwait(ev("system", "heartbeat", "10.0.0.101", null), () -> {
                callbackRan.set(true);
            }).get(3, TimeUnit.SECONDS);
            assertTrue(callbackRan.get());
        }
    }

    @Test
    void partialStateRestoreRollsBackEarlierRules() {
        TestStatefulRule first = new TestStatefulRule("first", "old", false);
        TestStatefulRule failing = new TestStatefulRule("failing", "old", true);
        try (RuleEngine engine = new RuleEngine(List.of(first, failing), List.of())) {
            List<String> restored = engine.restoreStates(Map.of(
                    "first", new RuleEngine.RuleState("first", "v1", bytes("new")),
                    "failing", new RuleEngine.RuleState("failing", "v1", bytes("bad"))));

            assertTrue(restored.isEmpty());
            assertEquals("old", first.state);
        }
    }

    @Test
    void explicitStateCompatibilityVersionControlsSnapshotRestore() {
        TestStatefulRule rule = new TestStatefulRule("versioned", "old", false);
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(), null, null,
                RuleExecutionScope.NOOP, null, Map.of("versioned", "v1:semantic-new"))) {
            assertTrue(engine.restoreStates(Map.of("versioned",
                    new RuleEngine.RuleState("versioned", "v1:semantic-old", bytes("new")))).isEmpty());
            assertEquals("old", rule.state);
            assertEquals("v1:semantic-new", engine.snapshotStates().get("versioned").version());
        }
    }

    /**
     * A durable event used to serialize every stateful rule three times: once as
     * the whole-event rollback baseline, once again as that rule's own rollback
     * baseline, and once more to compute the state-change digest. The first two
     * are the same bytes at the same instant, so the third call was pure work.
     * This pins the count so the duplication cannot come back unnoticed.
     */
    @Test
    void aDurableEventSerializesEachStatefulRuleTwiceNotThreeTimes() throws Exception {
        AtomicInteger snapshots = new AtomicInteger();
        StatefulRule counted = new StatefulRule() {
            @Override public String id() { return "COUNTED"; }
            @Override public String name() { return "counted"; }
            @Override public void accept(SecurityEvent event) { }
            @Override public List<Alert> drain() { return List.of(); }
            @Override public String stateVersion() { return "v1"; }
            @Override public byte[] snapshotState() {
                snapshots.incrementAndGet();
                return bytes("state-" + snapshots.get());
            }
            @Override public void restoreState(byte[] serializedState) { }
        };
        try (RuleEngine engine = new RuleEngine(List.of(counted), List.of())) {
            engine.start();
            engine.ingestAndAwait(ev("system", "snapshot-count", "count-host", null))
                    .get(3, TimeUnit.SECONDS);
        }

        // The rollback baseline reused as this rule's own baseline, plus the
        // state-change digest. A third would mean the full state is serialized
        // twice for the same instant.
        assertEquals(2, snapshots.get(),
                "a durable event must serialize each stateful rule twice, not three times");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class TestStatefulRule implements StatefulRule {
        private final String id;
        private final boolean failOnBadState;
        private String state;

        private TestStatefulRule(String id, String state, boolean failOnBadState) {
            this.id = id;
            this.state = state;
            this.failOnBadState = failOnBadState;
        }

        @Override public String id() { return id; }
        @Override public String name() { return id; }
        @Override public void accept(SecurityEvent event) { }
        @Override public List<Alert> drain() { return List.of(); }
        @Override public String stateVersion() { return "v1"; }
        @Override public byte[] snapshotState() { return bytes(state); }
        @Override public void restoreState(byte[] serializedState) {
            String next = new String(serializedState, java.nio.charset.StandardCharsets.UTF_8);
            if (failOnBadState && "bad".equals(next)) throw new IllegalStateException("corrupt state");
            state = next;
        }
    }

    @Test
    void asynchronousWorkerInstallsAndClosesEventExecutionScope() throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        AtomicBoolean closed = new AtomicBoolean();
        EventAlertSink sink = new EventAlertSink() {
            @Override
            public void publish(SecurityEvent event, List<Alert> alerts) {
                assertEquals(event.id(), context.get());
            }

            @Override
            public void close() {
            }
        };
        RuleExecutionScope scope = event -> {
            context.set(event.id());
            return () -> {
                context.remove();
                closed.set(true);
            };
        };
        try (RuleEngine engine = new RuleEngine(
                Rules.defaultRules(), List.of(sink), null, null, scope)) {
            engine.start();
            engine.ingestAndAwait(ev("system", "heartbeat", "10.0.0.101", null))
                    .get(3, TimeUnit.SECONDS);
            assertTrue(closed.get());
        }
    }

    @Test
    void closeDrainsAcceptedDurableWorkAndRejectsNewSubmissions() throws Exception {
        EventAlertSink slowSink = new EventAlertSink() {
            @Override
            public void publish(SecurityEvent event, List<Alert> alerts) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }

            @Override
            public void close() {
            }
        };
        RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(slowSink));
        engine.start();
        engine.start(); // lifecycle start is idempotent; never add a second state worker
        List<java.util.concurrent.CompletableFuture<Void>> accepted = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            accepted.add(engine.ingestAndAwait(
                    ev("system", "close-drain-" + i, "host-" + i, null)));
        }

        engine.close();

        for (var completion : accepted) completion.get(1, TimeUnit.SECONDS);
        RuleEngine.Submission rejected = engine.submit(
                ev("system", "after-close", "host-x", null), true);
        assertFalse(rejected.accepted());
        assertTrue(rejected.completion().isCompletedExceptionally());
        assertThrows(IllegalStateException.class, engine::start);
    }

    /**
     * 回滚阶段的清理本身可能失败：某个规则 drain 不出候选告警时，引擎必须
     * （1）继续丢弃其余规则的候选告警，（2）仍然完成状态回滚，（3）把原始失败
     * 原样交给调用方，而不是让清理异常顶掉真正的故障原因。
     */
    @Test
    void aFailingCandidateDrainDuringRollbackKeepsTheOriginalFailureAndStillCleansUp() throws Exception {
        AtomicInteger tailDrains = new AtomicInteger();
        CollectingSink sink = new CollectingSink();
        ThresholdRule emitter = new ThresholdRule("DISCARD-EMITTER", "emitter", ignored -> true,
                SecurityEvent::host, 2, Duration.ofMinutes(5), Severity.HIGH, "threshold");
        Rule failing = new Rule() {
            private final AtomicInteger accepts = new AtomicInteger();

            @Override public String id() { return "DISCARD-FAILING"; }
            @Override public String name() { return "failing"; }
            @Override public void accept(SecurityEvent event) {
                if (accepts.incrementAndGet() == 1) {
                    throw new IllegalStateException("dependency unavailable");
                }
            }
            @Override public List<Alert> drain() { return List.of(); }
        };
        Rule failingDrain = new Rule() {
            private final AtomicInteger drains = new AtomicInteger();

            @Override public String id() { return "DISCARD-BOOM"; }
            @Override public String name() { return "boom"; }
            @Override public void accept(SecurityEvent event) { }
            @Override public List<Alert> drain() {
                // 排在失败规则之后，正常候选收集根本走不到这里：第一次 drain
                // 调用就是回滚阶段的清理。
                if (drains.incrementAndGet() == 1) {
                    throw new IllegalStateException("discard unavailable");
                }
                return List.of();
            }
        };
        Rule tail = new Rule() {
            @Override public String id() { return "DISCARD-TAIL"; }
            @Override public String name() { return "tail"; }
            @Override public void accept(SecurityEvent event) { }
            @Override public List<Alert> drain() {
                tailDrains.incrementAndGet();
                return List.of();
            }
        };
        try (EngineLogs logs = new EngineLogs();
             RuleEngine engine = new RuleEngine(
                     List.of(emitter, failing, failingDrain, tail), List.of(sink))) {
            engine.start();
            SecurityEvent event = ev("auth", "failure", "discard-host", null);

            java.util.concurrent.ExecutionException failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS));

            assertEquals("dependency unavailable", failure.getCause().getMessage(),
                    "清理阶段的异常不得顶掉真正的失败原因");
            assertEquals(1, tailDrains.get(),
                    "一个规则 drain 失败后，其余规则的候选告警仍必须被丢弃");
            assertTrue(sink.alerts.isEmpty(), "回滚的事件不得投递告警");
            assertEquals(1, logs.messages(Level.WARN).stream()
                            .filter(message -> message.contains("DISCARD-BOOM")
                                    && message.contains("discard unavailable"))
                            .count(),
                    "清理失败必须留下点名规则的诊断日志，实际=" + logs.messages(Level.WARN));
            assertEquals(0, engine.alertCount(), "被回滚的事件不得计入已投递告警");

            // 回滚确实完成了：失败事件不留在窗口里，所以第三次成功事件才凑满阈值 2。
            engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS);
            assertTrue(sink.alerts.isEmpty(), "失败事件不得计入重试事件的阈值窗口");
            engine.ingestAndAwait(ev("auth", "failure-2", "discard-host", null))
                    .get(3, TimeUnit.SECONDS);
            assertEquals(1, sink.alerts.size());
            assertEquals(2, sink.alerts.getFirst().evidence().size(),
                    "告警证据必须是两次成功事件，失败的那次已被回滚");
            assertEquals(1, engine.alertCount());
            assertEquals(3, engine.eventCount(), "清理阶段失败不得让同一事件被重复处理计数");
        }
    }

    /**
     * 路由诊断是可观测性，不是事件路径的依赖：观测器抛错时引擎必须继续投递本事件
     * 的告警、保留已发生的报告窗口，并把异常留在日志里而不是静默吞掉或冒泡成事件失败。
     */
    @Test
    void aFailingRoutingObserverNeitherDisturbsTheEventNorReopensTheReportWindow() throws Exception {
        AtomicInteger observerCalls = new AtomicInteger();
        RuleProcessingObserver brokenObserver = new RuleProcessingObserver() {
            @Override public void routingMismatched(SecurityEvent event, String ruleId,
                                                    String declaredField, String eventRoutingField) {
                observerCalls.incrementAndGet();
                throw new IllegalStateException("metrics endpoint down");
            }
        };
        CollectingSink sink = new CollectingSink();
        ThresholdRule rule = new ThresholdRule("USER-GROUP", "user group", ignored -> true,
                SecurityEvent::host, 1, Duration.ofMinutes(5), Severity.HIGH, "threshold");
        try (EngineLogs logs = new EngineLogs();
             RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink), null, brokenObserver,
                     RuleExecutionScope.NOOP, null, Map.of(),
                     Map.of("USER-GROUP", new RuleEngine.RoutingDimension("user", 3600)))) {
            engine.start();
            engine.ingestAndAwait(ev("auth", "one", "10.0.0.1", null)).get(3, TimeUnit.SECONDS);

            assertEquals(1, observerCalls.get());
            assertEquals(1, sink.alerts.size(), "观测器抛错不得吞掉本已产出的告警");
            assertEquals(1, engine.routingMismatchWindows(),
                    "窗口计数发生在观测器之前，不得被其失败抹掉");
            assertEquals(1, logs.messages(Level.DEBUG).stream()
                            .filter(message -> message.contains("metrics endpoint down"))
                            .count(),
                    "观测器失败必须留在日志里，实际=" + logs.messages(Level.DEBUG));

            // 失败不得重置节流：同一窗口内的第二个事件仍然只报告一次。
            engine.ingestAndAwait(ev("auth", "two", "10.0.0.2", null)).get(3, TimeUnit.SECONDS);
            assertEquals(1, observerCalls.get(), "抛错不得让同一窗口重复报告");
            assertEquals(1, engine.routingMismatchWindows());
            assertEquals(2, sink.alerts.size(), "引擎必须继续正常服务后续事件");
        }
    }

    /**
     * 只实现部分回调的观测器（例如仅统计评估边界）依赖接口的默认空实现：
     * 路由诊断落到默认方法时必须是彻底无操作——既不能改变计数，也不能干扰投递。
     */
    @Test
    void theDefaultRoutingObserverHookIsANoOpForPartialImplementations() throws Exception {
        List<Integer> evaluations = new CopyOnWriteArrayList<>();
        RuleProcessingObserver partialObserver = new RuleProcessingObserver() {
            @Override public void evaluationCompleted(SecurityEvent event, int emittedAlerts) {
                evaluations.add(emittedAlerts);
            }
            // routingMismatched 未覆写：走 RuleProcessingObserver 的默认空实现
        };
        CollectingSink sink = new CollectingSink();
        ThresholdRule rule = new ThresholdRule("USER-GROUP", "user group", ignored -> true,
                SecurityEvent::host, 1, Duration.ofMinutes(5), Severity.HIGH, "threshold");
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink), null, partialObserver,
                RuleExecutionScope.NOOP, null, Map.of(),
                Map.of("USER-GROUP", new RuleEngine.RoutingDimension("user", 3600)))) {
            engine.start();
            engine.ingestAndAwait(ev("auth", "one", "10.0.0.1", null)).get(3, TimeUnit.SECONDS);

            assertEquals(List.of(1), evaluations, "默认实现必须让事件路径正常走到评估边界");
            assertEquals(1, engine.routingMismatchWindows(), "默认空实现不得改动诊断计数");
            assertEquals(1, sink.alerts.size());
            assertEquals(1L, reportedWindowsOf(engine, "USER-GROUP"));
        }
    }

    /**
     * 热更新用 awaitIdle 在读取 journal 前排空在途工作。队列满到连屏障都排不进去时，
     * 它必须回答「没有空闲」，否则替换代际会漏掉仍在队列里的事件。
     */
    @Test
    void awaitIdleReportsNotIdleWhenTheSaturatedEngineCannotAcceptTheBarrier() {
        Rule probe = routingProbe("SATURATE");
        SecurityEvent event = ev("system", "saturate", "saturate-host", null);
        // 故意不 start()，也不 close()：没有 worker 就没有线程需要回收，而 close()
        // 在满队列上会永远阻塞在 put(poison) —— 那正是它 fail-closed 的证明。
        RuleEngine engine = new RuleEngine(List.of(probe), List.of());
        int admitted = 0;
        while (admitted <= 200_000 && engine.submit(event, false).accepted()) {
            admitted++;
        }

        try (EngineLogs logs = new EngineLogs()) {
            assertEquals(100_000, admitted, "背压契约：填满 100_000 槽位后必须拒绝接收");
            assertEquals(1.0, engine.queueLoad(), 0.0001);
            long dropsBefore = engine.dropCount();

            assertFalse(engine.awaitIdle(5),
                    "排不入空闲屏障时必须报告未空闲，不得让热更新带着未处理工作去读 journal");

            assertEquals(dropsBefore, engine.dropCount(),
                    "屏障被拒不是事件丢弃，不得污染 drop 统计");
            assertEquals(1, logs.messages(Level.WARN).stream()
                            .filter(message -> message.contains("idle barrier"))
                            .count(),
                    "饱和必须留下可诊断的 WARN，实际=" + logs.messages(Level.WARN));
            assertEquals(1.0, engine.queueLoad(), 0.0001,
                    "屏障失败不得顺手丢掉已排队的工作");
        }
    }

    /**
     * awaitIdle 会阻塞在屏障上：调用线程被中断时必须立刻放弃等待、交还中断信号，
     * 并且既不能谎报空闲，也不能把引擎弄成不可再排空的状态。
     */
    @Test
    void awaitIdleHandsTheInterruptBackToTheCallerInsteadOfReportingIdle() throws Exception {
        try (RuleEngine engine = new RuleEngine(List.of(routingProbe("IDLE-INTERRUPT")), List.of())) {
            engine.start();
            assertTrue(engine.awaitIdle(2000), "未中断时空闲屏障必须成立");

            Thread.currentThread().interrupt();
            boolean idle;
            try {
                idle = engine.awaitIdle(30_000);
            } finally {
                assertTrue(Thread.interrupted(), "awaitIdle 必须保留调用线程的中断信号");
            }

            assertFalse(idle, "被中断时不得报告已排空");
            assertEquals(0, engine.dropCount(), "屏障不是事件，被中断也不得计入 drop");
            assertTrue(engine.awaitIdle(3000), "一次中断不得让引擎永久失去排空能力");
        }
    }

    /**
     * 重建规则后，同 id 的规则实例是新一代状态：路由诊断的节流窗口必须随之清零，
     * 否则新代际会继承上一代未走完的 3600s 窗口而永远不再报告。
     */
    @Test
    void reloadResetsTheRoutingDiagnosticWindowOfTheReplacedRuleGeneration() throws Exception {
        try (RuleEngine engine = new RuleEngine(List.of(routingProbe("USER-GROUP")), List.of(),
                null, null, RuleExecutionScope.NOOP, null, Map.of(),
                Map.of("USER-GROUP", new RuleEngine.RoutingDimension("user", 3600)))) {
            engine.start();
            engine.ingestAndAwait(ev("auth", "one", "10.0.0.1", null)).get(3, TimeUnit.SECONDS);
            engine.ingestAndAwait(ev("auth", "two", "10.0.0.2", null)).get(3, TimeUnit.SECONDS);
            assertEquals(1, engine.routingMismatchWindows(), "同一窗口内只报告一次");
            assertEquals(1L, reportedWindowsOf(engine, "USER-GROUP"));

            engine.reload(List.of(routingProbe("USER-GROUP")));

            assertEquals(0L, reportedWindowsOf(engine, "USER-GROUP"),
                    "新代际的规则必须从干净的诊断窗口开始");
            assertEquals(1, engine.routingMismatchWindows(), "引擎累计计数不得被重建抹掉");

            engine.ingestAndAwait(ev("auth", "three", "10.0.0.3", null)).get(3, TimeUnit.SECONDS);
            assertEquals(2, engine.routingMismatchWindows(),
                    "同 id 新实例必须能立即再报一次，而不是继承上一代未走完的窗口");
            assertEquals(1L, reportedWindowsOf(engine, "USER-GROUP"));
        }
    }

    /** 一个不产出告警的规则，用于只验证路由诊断/背压等非告警语义。 */
    private static Rule routingProbe(String id) {
        return new Rule() {
            @Override public String id() { return id; }
            @Override public String name() { return "routing probe " + id; }
            @Override public void accept(SecurityEvent event) { }
            @Override public List<Alert> drain() { return List.of(); }
        };
    }

    private static long reportedWindowsOf(RuleEngine engine, String ruleId) {
        Map<String, Object> stats = engine.ruleStats().stream()
                .filter(item -> ruleId.equals(item.get("id"))).findFirst().orElseThrow();
        return (Long) stats.get("routingMismatchWindows");
    }
}
