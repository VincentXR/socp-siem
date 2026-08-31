package com.socp.rule;

import com.socp.rule.config.Rules;
import com.socp.rule.engine.AlertSink;
import com.socp.rule.engine.EventAlertSink;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleExecutionScope;
import com.socp.rule.engine.Suppressor;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
}
