package com.socp.platform.auth.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint as an internal service boundary.
 *
 * <p>A valid user JWT is not sufficient for this endpoint.  Callers must also
 * present the short-lived HMAC service proof verified by {@link AuthInterceptor}.
 * This keeps an analyst token from being used to invoke a downstream side
 * effect directly.</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireService {
    String SERVICE_ID_ATTRIBUTE = RequireService.class.getName() + ".serviceId";

    String message() default "Service identity is required";
}
