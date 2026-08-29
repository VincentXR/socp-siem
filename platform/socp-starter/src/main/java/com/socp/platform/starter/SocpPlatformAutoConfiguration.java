package com.socp.platform.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Explicit servlet-side integration for the shared SOCP platform.
 *
 * <p>Business applications should scan only their own domain package and add
 * this starter.  Keeping the platform package list here makes the dependency
 * surface auditable and prevents a new platform class from being pulled into
 * every application accidentally.</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(SocpOpenApiConfiguration.class)
@ComponentScan(basePackages = {
        "com.socp.platform.auth",
        "com.socp.platform.tenant",
        "com.socp.platform.audit",
        "com.socp.platform.ratelimit",
        "com.socp.platform.obs",
        "com.socp.platform.error",
        "com.socp.platform.data",
        "com.socp.platform.rule",
        "com.socp.platform.client"
})
public class SocpPlatformAutoConfiguration {
}
