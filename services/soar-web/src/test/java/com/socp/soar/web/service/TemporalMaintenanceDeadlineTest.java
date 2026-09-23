package com.socp.soar.web.service;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TemporalMaintenanceDeadlineTest {
    @Test @Timeout(10)
    void workflowAbsenceAndNamespaceFailureRemainDistinctAcrossTheGrpcTransport() throws Exception {
        String name = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new WorkflowServiceGrpc.WorkflowServiceImplBase() {
                    @Override public void getSystemInfo(io.temporal.api.workflowservice.v1.GetSystemInfoRequest request,
                            StreamObserver<io.temporal.api.workflowservice.v1.GetSystemInfoResponse> response) {
                        response.onNext(io.temporal.api.workflowservice.v1.GetSystemInfoResponse.getDefaultInstance());
                        response.onCompleted();
                    }
                    @Override public void describeWorkflowExecution(DescribeWorkflowExecutionRequest request,
                            StreamObserver<DescribeWorkflowExecutionResponse> response) {
                        assertEquals("wire-test", request.getNamespace());
                        if ("absent-workflow".equals(request.getExecution().getWorkflowId())) {
                            response.onError(io.temporal.serviceclient.StatusUtils.newException(Status.NOT_FOUND.withDescription("absent"),
                                    io.temporal.api.errordetails.v1.NotFoundFailure.getDefaultInstance(),
                                    io.temporal.api.errordetails.v1.NotFoundFailure.getDescriptor()));
                        } else {
                            response.onError(io.temporal.serviceclient.StatusUtils.newException(Status.NOT_FOUND.withDescription("namespace absent"),
                                    io.temporal.api.errordetails.v1.NamespaceNotFoundFailure.getDefaultInstance(),
                                    io.temporal.api.errordetails.v1.NamespaceNotFoundFailure.getDescriptor()));
                        }
                    }
                }).build().start();
        var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setChannel(channel).build());
        try {
            var client = WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder().setNamespace("wire-test").build());
            var wireFailure = assertThrows(StatusRuntimeException.class, () -> service.blockingStub()
                    .withDeadlineAfter(3, TimeUnit.SECONDS).describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace("wire-test").setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                    .setWorkflowId("absent-workflow")).build()));
            assertEquals(Status.Code.NOT_FOUND, wireFailure.getStatus().getCode(), wireFailure.toString());
            var executor = new TemporalExecutor(client, true, "unused-in-process");
            assertEquals(TemporalExecutor.WorkflowState.NOT_FOUND, executor.describeWorkflow("absent-workflow"));
            assertEquals(TemporalExecutor.WorkflowState.UNKNOWN, executor.describeWorkflow("namespace-failure"));
        } finally {
            service.shutdownNow();
            channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(15)
    void silentServerCannotHoldDescribeOrCancellationBeyondTheRpcDeadline(boolean stallHandshake) throws Exception {
        var observedDeadlineMillis = new AtomicLong();
        String name = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new WorkflowServiceGrpc.WorkflowServiceImplBase() {
                    @Override public void getSystemInfo(io.temporal.api.workflowservice.v1.GetSystemInfoRequest request,
                            StreamObserver<io.temporal.api.workflowservice.v1.GetSystemInfoResponse> response) {
                        if (stallHandshake) {
                            observedDeadlineMillis.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                            return;
                        }
                        response.onNext(io.temporal.api.workflowservice.v1.GetSystemInfoResponse.getDefaultInstance());
                        response.onCompleted();
                    }
                    @Override public void describeWorkflowExecution(DescribeWorkflowExecutionRequest request,
                            StreamObserver<DescribeWorkflowExecutionResponse> response) {
                        observedDeadlineMillis.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                        // Deliberately never answer, including after client cancellation.
                    }
                    @Override public void signalWorkflowExecution(SignalWorkflowExecutionRequest request,
                            StreamObserver<SignalWorkflowExecutionResponse> response) {
                        observedDeadlineMillis.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                    }
                }).build().start();
        var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setChannel(channel).build());
        try {
            var client = WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder()
                    .setNamespace("deadline-test").setIdentity("test-worker").build());
            var executor = new TemporalExecutor(client, true, "unused-in-process");
            long start = System.nanoTime();
            assertEquals(TemporalExecutor.WorkflowState.UNKNOWN, executor.describeWorkflow("run"));
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 8);
            assertTrue(observedDeadlineMillis.get() > 0 && observedDeadlineMillis.get() <= 3000);
            start = System.nanoTime();
            var failure = assertThrows(StatusRuntimeException.class, () -> executor.cancelWorkflow("run"));
            assertEquals(Status.Code.DEADLINE_EXCEEDED, failure.getStatus().getCode());
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 8);
            assertTrue(observedDeadlineMillis.get() > 0 && observedDeadlineMillis.get() <= 3000);
        } finally {
            service.shutdownNow();
            channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
        }
    }
}
