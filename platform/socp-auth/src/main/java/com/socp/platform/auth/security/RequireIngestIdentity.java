package com.socp.platform.auth.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the canonical event-ingest boundary.
 *
 * <p>The request must be authenticated either with a registered collector
 * credential or with a signed internal service identity.  Ordinary user JWTs
 * cannot inject events just because they have an analyst role.</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireIngestIdentity {
    String message() default "Collector or service identity is required";
}
