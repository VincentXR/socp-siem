package com.socp.detect.web.service;

import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.state.DetectionStateSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DetectionRuntimeWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(RuntimeComponents.class)
            .withPropertyValues("socp.detect.state.retention=30m", "socp.detect.state.shards=4",
                    "socp.detect.state.snapshot-every-events=7", "socp.detect.input-topic=routed-events")
            .withBean(RuleSpecStore.class, () -> mock(RuleSpecStore.class))
            .withBean(RecentAlertSink.class, () -> new RecentAlertSink(10, null, null))
            .withBean(DetectionStateStore.class, () -> mock(DetectionStateStore.class))
            .withBean(DetectionStateSnapshotStore.class, () -> mock(DetectionStateSnapshotStore.class))
            .withBean(DetectionStateOwnership.class, DetectionStateOwnership::noop)
            .withBean(DetectionPerformanceMetrics.class, () -> mock(DetectionPerformanceMetrics.class))
            .withBean(RuleChangePublisher.class, () -> mock(RuleChangePublisher.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void springWiresTheOwningComponentsWithoutStartingWorkersOnTheApiRole() {
        runner.withPropertyValues("socp.detect.runtime-role=api").run(context -> {
            assertThat(context).hasNotFailed();
            var runtime = context.getBean(DetectEngineService.class);
            var lifecycle = context.getBean(DetectionEngineLifecycle.class);
            var state = context.getBean(DetectionStateRecovery.class);
            assertThat(AopUtils.isAopProxy(context.getBean(DetectionRuleService.class))).isTrue();
            assertThat(runtime.isReady()).isTrue();
            assertThat(lifecycle.cachedTenantEngines()).isZero();
            assertThat(runtime.ingestFromKafka(event())).isFalse();
            assertThat(state.inputTopic()).isEqualTo("routed-events");
            assertThat(state.snapshotStats()).containsEntry("shards", 4).containsEntry("everyEvents", 7L);
            // Spring wraps scheduled runnables for outcome tracking; the exposed
            // task descriptions retain the declaring owner and method.
            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()
                    .stream().map(Object::toString).toList();
            assertThat(tasks).filteredOn(name -> name.startsWith(DetectionEngineLifecycle.class.getName() + "."))
                    .containsExactlyInAnyOrder(DetectionEngineLifecycle.class.getName() + ".retryDegradedEngines",
                            DetectionEngineLifecycle.class.getName() + ".evictIdleEngines");
            assertThat(tasks).noneMatch(name -> name.startsWith(DetectEngineService.class.getName() + "."));
            verifyNoInteractions(context.getBean(DetectionStateStore.class),
                    context.getBean(DetectionStateSnapshotStore.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "worker"})
    void workerRolesStillAdmitBothKafkaEntryPoints(String role) {
        runner.withPropertyValues("socp.detect.runtime-role=" + role).run(context -> {
            assertThat(context).hasNotFailed();
            var runtime = context.getBean(DetectEngineService.class);
            assertThat(runtime.ingestFromKafka(event())).isTrue();
            runtime.ingestFromKafkaAndAwait(event()).join();
            assertThat(context.getBean(DetectionEngineLifecycle.class).cachedTenantEngines()).isPositive();
        });
    }

    private static SecurityEvent event() {
        return new SecurityEvent("wiring-event", Instant.now(), "auth", "host-1", "test",
                Map.of("tenant_id", "default"), Severity.INFO);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({DetectEngineService.class, DetectionRuleService.class,
            DetectionEngineLifecycle.class, DetectionStateRecovery.class})
    @EnableTransactionManagement
    @EnableScheduling
    static class RuntimeComponents { }
}
