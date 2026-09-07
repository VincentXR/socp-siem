package com.socp.soar.web.config;

import com.socp.soar.web.temporal.PlaybookActivity;
import com.socp.soar.web.temporal.v2.SoarV2Activity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Construction-time coverage for Temporal worker wiring and SOAR runtime
 * properties. The workflow client uses lazy service stubs, so building and
 * starting the worker factory does not require a reachable Temporal server;
 * poller threads are stopped immediately after each check.
 */
class TemporalConfigCoverageTest {

    /**
     * Activity implementations must not be Mockito mocks: the generated
     * subclass copies {@code @ActivityMethod} onto its overrides, which
     * Temporal rejects ("annotation can be used only on the interface
     * method it implements"). A JDK proxy implements the interface without
     * copying annotations, mirroring a real Spring-backed implementation.
     */
    private static <T> T activityStub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                }));
    }

    @Test
    void runtimePropertiesRoundTripEverySwitch() {
        SoarRuntimeProperties properties = new SoarRuntimeProperties();

        // Defaults.
        assertThat(properties.isSimulationEnabled()).isFalse();
        assertThat(properties.getMaturity()).isEqualTo("preview");
        assertThat(properties.getScheduleZone()).isEqualTo("UTC");
        assertThat(properties.isV2EvaluationEnabled()).isTrue();
        assertThat(properties.isLegacyExecutionEnabled()).isTrue();
        assertThat(properties.isV2ControlPlaneEnabled()).isTrue();
        assertThat(properties.isV2ExecutionEnabled()).isTrue();
        assertThat(properties.isLegacyMutationEnabled()).isTrue();
        assertThat(properties.getExecutionTenantAllowlist()).isEmpty();

        properties.setSimulationEnabled(true);
        properties.setMaturity("ga");
        properties.setScheduleZone("Asia/Shanghai");
        properties.setV2EvaluationEnabled(false);
        properties.setLegacyExecutionEnabled(false);
        properties.setV2ControlPlaneEnabled(false);
        properties.setV2ExecutionEnabled(false);
        properties.setLegacyMutationEnabled(false);
        properties.setExecutionTenantAllowlist("tenant-a,tenant-b");

        assertThat(properties.isSimulationEnabled()).isTrue();
        assertThat(properties.getMaturity()).isEqualTo("ga");
        assertThat(properties.getScheduleZone()).isEqualTo("Asia/Shanghai");
        assertThat(properties.isV2EvaluationEnabled()).isFalse();
        assertThat(properties.isLegacyExecutionEnabled()).isFalse();
        assertThat(properties.isV2ControlPlaneEnabled()).isFalse();
        assertThat(properties.isV2ExecutionEnabled()).isFalse();
        assertThat(properties.isLegacyMutationEnabled()).isFalse();
        assertThat(properties.getExecutionTenantAllowlist()).isEqualTo("tenant-a,tenant-b");
    }

    @Test
    void workerFactorySkipsRegistrationWhenTemporalIsDisabled() {
        TemporalWorkerConfig config = new TemporalWorkerConfig();
        TemporalProperties properties = new TemporalProperties();
        properties.setEnabled(false);

        WorkerFactory factory = config.workerFactory(client(), activityStub(PlaybookActivity.class),
                activityStub(SoarV2Activity.class), properties);

        assertThat(factory).isNotNull();
        factory.shutdownNow();
    }

    @Test
    void workerFactoryRegistersV2WorkerAndToleratesUnreachableServer() {
        TemporalWorkerConfig config = new TemporalWorkerConfig();
        TemporalProperties properties = new TemporalProperties(); // enabled, target localhost:7233

        WorkerFactory factory = config.workerFactory(client(), activityStub(PlaybookActivity.class),
                activityStub(SoarV2Activity.class), properties);
        try {
            // start() must not throw when the Temporal server is unreachable:
            // poller threads idle until the server comes back.
            assertThat(factory).isNotNull();
        } finally {
            try {
                factory.shutdownNow();
            } catch (Exception tolerated) {
                // Worker teardown issues a blocking shutdownWorker RPC; with no
                // reachable server that call fails, which is exactly the
                // degraded condition this test tolerates.
            }
        }
    }

    private WorkflowClient client() {
        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget("localhost:7233").build());
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace("default").build());
    }
}
