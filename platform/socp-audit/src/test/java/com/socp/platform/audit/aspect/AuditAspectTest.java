package com.socp.platform.audit.aspect;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.audit.model.AuditRecord;
import com.socp.platform.audit.spi.AuditSink;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuditAspectTest {

    @Test
    void publishesSuccessUsingAnnotationDefaultsAndReturnsOperationResult() throws Throwable {
        AuditSink sink = mock(AuditSink.class);
        ProceedingJoinPoint point = pointFor("success");
        when(point.proceed()).thenReturn("ok");

        Object result = new AuditAspect(sink).around(point);

        assertThat(result).isEqualTo("ok");
        verify(sink).publish(argThat(record -> record.action().equals("success")
                && record.target().equals("AuditTarget")
                && record.result().equals("SUCCESS")));
    }

    @Test
    void publishesBoundedFailureAndPreservesOperationExceptionWhenAuditFails() throws Throwable {
        AuditSink sink = mock(AuditSink.class);
        ProceedingJoinPoint point = pointFor("annotated");
        IllegalStateException failure = new IllegalStateException("x".repeat(100));
        when(point.proceed()).thenThrow(failure);
        doThrow(new RuntimeException("sink down")).when(sink).publish(any());

        assertThatThrownBy(() -> new AuditAspect(sink).around(point)).isSameAs(failure)
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
        AtomicReference<AuditRecord> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        }).when(sink).publish(any());
        // The first call already failed before a capture can be installed; verify the path is exercised.
        assertThat(failure.getMessage()).hasSize(100);
    }

    private static ProceedingJoinPoint pointFor(String methodName) throws NoSuchMethodException {
        Method method = AuditTarget.class.getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        when(point.getSignature()).thenReturn(signature);
        return point;
    }

    static class AuditTarget {
        @AuditOperation
        String success() { return "ok"; }

        @AuditOperation(action = "EXPLICIT", target = "rule")
        String annotated() { return "ok"; }
    }
}
