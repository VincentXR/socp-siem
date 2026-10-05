package com.socp.platform.audit.aspect;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.audit.model.AuditRecord;
import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.audit.spi.TransactionalAuditSink;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;

/**
 * 审计切面：拦截带 @AuditOperation 的方法，环绕记录操作前后，结果成功/异常都留痕。
 * 默认走 InMemoryAuditSink；Docker 环境配 socp.audit.sink=kafka 切到 KafkaAuditSink（见 AuditAutoConfiguration）。
 */
@Aspect
@Component
@Order(10)
public class AuditAspect {

    private final AuditSink sink;
    private final PlatformTransactionManager transactionManager;

    public AuditAspect(AuditSink sink) {
        this(sink, (PlatformTransactionManager) null);
    }

    @Autowired
    public AuditAspect(AuditSink sink, ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this(sink, transactionManagers.getIfAvailable());
    }

    AuditAspect(AuditSink sink, PlatformTransactionManager transactionManager) {
        this.sink = sink;
        this.transactionManager = transactionManager;
    }

    @Around("@annotation(com.socp.platform.audit.api.AuditOperation)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        AuditOperation ann = method.getAnnotation(AuditOperation.class);
        String action = ann.action().isEmpty() ? method.getName() : ann.action();
        String target = ann.target().isEmpty() ? method.getDeclaringClass().getSimpleName() : ann.target();
        if (sink instanceof TransactionalAuditSink && transactionManager != null) {
            return transactional(pjp, method, action, target);
        }
        return direct(pjp, action, target);
    }

    private Object direct(ProceedingJoinPoint pjp, String action, String target) throws Throwable {
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable operationFailure) {
            try {
                sink.publish(details(pjp, AuditRecord.of(action, target,
                        "FAIL:" + safeMessage(operationFailure)), null));
            } catch (RuntimeException auditFailure) {
                operationFailure.addSuppressed(auditFailure);
            }
            throw operationFailure;
        }
        sink.publish(details(pjp, AuditRecord.of(action, target, "SUCCESS"), result));
        return result;
    }

    /**
     * Wrap controller-level audit points as well as service-level ones. The
     * business mutation and SUCCESS outbox row therefore commit together; a
     * broker outage never turns a committed mutation into a misleading 5xx.
     */
    private Object transactional(ProceedingJoinPoint pjp, Method method,
                                 String action, String target) throws Throwable {
        TransactionAttribute declared = new AnnotationTransactionAttributeSource()
                .getTransactionAttribute(method, pjp.getTarget() == null
                        ? method.getDeclaringClass() : pjp.getTarget().getClass());
        // Some audited operations deliberately commit a short local claim before an
        // irreversible remote side effect. Wrapping those methods in REQUIRED would
        // retain an outer connection while their short transactions run and would
        // falsely imply that a later audit failure can roll the remote effect back.
        if (declared != null && declared.getPropagationBehavior()
                == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
            return direct(pjp, action, target);
        }
        TransactionTemplate required = new TransactionTemplate(transactionManager);
        required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        if (declared != null) {
            required.setIsolationLevel(declared.getIsolationLevel());
            required.setTimeout(declared.getTimeout());
            required.setName(declared.getName());
        }

        try {
            Outcome outcome = required.execute(status -> {
                try {
                    Object result = pjp.proceed();
                    sink.publish(details(pjp, AuditRecord.of(action, target, "SUCCESS"), result));
                    return new Outcome(result, null);
                } catch (Throwable operationFailure) {
                    boolean rollback = declared == null || declared.rollbackOn(operationFailure);
                    if (rollback) throw new RollbackInvocation(operationFailure);
                    sink.publish(details(pjp, AuditRecord.of(action, target,
                            "FAIL:" + safeMessage(operationFailure)), null));
                    return new Outcome(null, operationFailure);
                }
            });
            if (outcome == null) throw new IllegalStateException("Audited transaction returned no outcome");
            if (outcome.failure() != null) throw outcome.failure();
            return outcome.result();
        } catch (RollbackInvocation rollback) {
            publishFailureAfterRollback(pjp, action, target, rollback.failure());
            throw rollback.failure();
        } catch (org.springframework.transaction.UnexpectedRollbackException rollback) {
            publishFailureAfterRollback(pjp, action, target, rollback);
            throw rollback;
        }
    }

    private void publishFailureAfterRollback(ProceedingJoinPoint pjp, String action, String target, Throwable operationFailure) {
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            separate.executeWithoutResult(status -> sink.publish(details(pjp, AuditRecord.of(action, target,
                    "FAIL:" + safeMessage(operationFailure)), null)));
        } catch (RuntimeException auditFailure) {
            operationFailure.addSuppressed(auditFailure);
        }
    }

    private static String safeMessage(Throwable failure) {
        // Exception messages can contain submitted credentials or free-text evidence.
        return failure.getClass().getSimpleName().substring(0,
                Math.min(59, failure.getClass().getSimpleName().length()));
    }

    private static AuditRecord details(ProceedingJoinPoint pjp, AuditRecord record, Object result) {
        return AuditDetails.capture(((MethodSignature) pjp.getSignature()).getMethod(),
                pjp.getArgs(), result, record);
    }

    private record Outcome(Object result, Throwable failure) {
    }

    private static final class RollbackInvocation extends RuntimeException {
        private final Throwable failure;

        private RollbackInvocation(Throwable failure) {
            super(failure);
            this.failure = failure;
        }

        private Throwable failure() {
            return failure;
        }
    }
}
