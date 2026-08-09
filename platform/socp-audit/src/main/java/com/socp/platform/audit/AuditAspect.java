package com.socp.platform.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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

    public AuditAspect(AuditSink sink) {
        this.sink = sink;
    }

    @Around("@annotation(com.socp.platform.audit.AuditOperation)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        AuditOperation ann = method.getAnnotation(AuditOperation.class);
        String action = ann.action().isEmpty() ? method.getName() : ann.action();
        String target = ann.target().isEmpty() ? method.getDeclaringClass().getSimpleName() : ann.target();
        try {
            Object r = pjp.proceed();
            sink.publish(AuditRecord.of(action, target, "SUCCESS"));
            return r;
        } catch (Throwable t) {
            sink.publish(AuditRecord.of(action, target, "FAIL:" + t.getMessage()));
            throw t;
        }
    }
}
