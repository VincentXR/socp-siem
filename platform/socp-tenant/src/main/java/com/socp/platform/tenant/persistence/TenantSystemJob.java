package com.socp.platform.tenant.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a reviewed background job that must discover or maintain data across
 * tenants. Adding a timer must never grant a database-wide RLS bypass
 * implicitly, so this annotation is deliberately separate from
 * {@code @Scheduled}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantSystemJob {
}
