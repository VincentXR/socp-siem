package com.socp.soar.web.config;

import com.socp.soar.web.service.TemporalExecutor;
import com.socp.soar.web.temporal.request.SoarWorkflowRequest;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.temporal.api.enums.v1.NamespaceState;
import io.temporal.api.namespace.v1.NamespaceInfo;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TemporalRpcBudgetTest {
    @Test
    @Timeout(30)
    void unresponsiveStartCannotHoldTheDurableDispatcherForTheSdkDefaultMinute() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        Server server = ServerBuilder.forPort(0).addService(new WorkflowServiceGrpc.WorkflowServiceImplBase() {
            @Override
            public void getSystemInfo(GetSystemInfoRequest request, StreamObserver<GetSystemInfoResponse> response) {
                response.onNext(GetSystemInfoResponse.getDefaultInstance());
                response.onCompleted();
            }
            @Override
            public void describeNamespace(DescribeNamespaceRequest request, StreamObserver<DescribeNamespaceResponse> response) {
                response.onNext(DescribeNamespaceResponse.newBuilder().setNamespaceInfo(NamespaceInfo.newBuilder()
                        .setName(request.getNamespace()).setState(NamespaceState.NAMESPACE_STATE_REGISTERED)).build());
                response.onCompleted();
            }
            @Override
            public void startWorkflowExecution(StartWorkflowExecutionRequest request,
                                               StreamObserver<StartWorkflowExecutionResponse> response) {
                starts.incrementAndGet(); // Deliberately never acknowledge a start.
            }
        }).build().start();
        var properties = new TemporalProperties();
        properties.setTarget("127.0.0.1:" + server.getPort());
        var client = new TemporalWorkerConfig().workflowClient(properties);
        try {
            var executor = new TemporalExecutor(client, properties);
            assertTrue(executor.isAvailable());
            long start = System.nanoTime();
            assertThrows(RuntimeException.class, () -> executor.startWorkflow(
                    new SoarWorkflowRequest("tenant-a", "run-1", "version-1", "{}", "{}"), "soar-tenant-a-run-1"));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(15)) < 0);
            assertTrue(starts.get() >= 1 && starts.get() <= 3);
        } finally {
            client.getWorkflowServiceStubs().shutdownNow();
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
