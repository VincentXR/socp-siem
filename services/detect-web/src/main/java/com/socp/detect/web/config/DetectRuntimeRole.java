package com.socp.detect.web.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Selects the management or continuous-processing surface of Detection.
 *
 * <p>The default {@code all} role keeps the local single-process profile
 * source-compatible. Production-shaped deployments use the same artifact as
 * an API process or as a Kafka/state/outbox worker.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(DetectRuntimeRoleCondition.class)
public @interface DetectRuntimeRole {

    Role[] value();

    enum Role {
        API,
        WORKER,
        ALL
    }
}
