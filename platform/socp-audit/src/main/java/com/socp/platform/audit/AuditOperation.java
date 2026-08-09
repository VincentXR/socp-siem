package com.socp.platform.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记需要审计的操作；切面拦截后产生 AuditRecord 并发布到 AuditSink。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditOperation {
    String action() default "";

    String target() default "";
}
