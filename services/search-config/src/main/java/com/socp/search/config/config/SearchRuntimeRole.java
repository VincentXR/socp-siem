package com.socp.search.config.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Selects which part of the SEARCH application is active in a deployment.
 *
 * <p>The default role is {@code all}, which preserves the single-process
 * development profile. Production can run the same artifact as an API
 * process or as a continuous-processing worker without creating a dependency
 * from the API request lifecycle to a Kafka consumer thread.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(SearchRuntimeRoleCondition.class)
public @interface SearchRuntimeRole {

    Role[] value();

    enum Role {
        API,
        WORKER,
        ALL
    }
}
