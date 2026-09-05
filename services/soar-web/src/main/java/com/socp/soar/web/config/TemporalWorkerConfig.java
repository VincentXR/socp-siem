package com.socp.soar.web.config;
import com.socp.soar.web.temporal.PlaybookActivity;
import com.socp.soar.web.temporal.PlaybookWorkflowImpl;
import com.socp.soar.web.temporal.v2.SoarV2Activity;
import com.socp.soar.web.temporal.v2.SoarV2Workflow;
import com.socp.soar.web.temporal.v2.SoarV2WorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal Worker 装配（持久运行模式）。
 *
 * <p>WorkflowClient 懒连接（连不上不抛错）；WorkerFactory.start() 即使服务端不可达
 * 也只是轮询线程空转，不阻塞 Spring 启动。真正的可用性判定在 {@link TemporalExecutor#isAvailable()}。
 * 开关：socp.temporal.enabled=false 时不启动 Worker；V2 运行仍保持持久化 QUEUED，不走进程内降级。
 */
@Configuration
public class TemporalWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerConfig.class);

    @Bean
    public WorkflowClient workflowClient(
            TemporalProperties properties) {
        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(properties.getTarget()).build());
        WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
                .setNamespace(properties.getNamespace())
                .build();
        return WorkflowClient.newInstance(stubs, options);
    }

    @Bean(destroyMethod = "shutdown")
    public WorkerFactory workerFactory(WorkflowClient client, PlaybookActivity activity,
                                       SoarV2Activity v2Activity,
                                       TemporalProperties properties) {
        if (!properties.isEnabled()) {
            log.info("socp.temporal.enabled=false，跳过 Temporal Worker");
            return WorkerFactory.newInstance(client);
        }
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(com.socp.soar.web.temporal.PlaybookWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(PlaybookWorkflowImpl.class);
        worker.registerActivitiesImplementations(activity);
        Worker v2Worker = factory.newWorker(SoarV2Workflow.TASK_QUEUE);
        v2Worker.registerWorkflowImplementationTypes(SoarV2WorkflowImpl.class);
        v2Worker.registerActivitiesImplementations(v2Activity);
        try {
            factory.start();
            log.info("Temporal Worker 已启动（taskQueue={}）", com.socp.soar.web.temporal.PlaybookWorkflow.TASK_QUEUE);
        } catch (Exception e) {
            // Keep the worker bean alive so the durable dispatcher can retry later.
            log.warn("Temporal Worker start failed; V2 runs remain queued: {}", e.getMessage());
        }
        return factory;
    }
}
