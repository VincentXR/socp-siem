package com.socp.platform.starter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Declares the platform-side JPA wiring so a business service does not have to
 * hand-copy the Spring Data scanning annotations onto every main class.
 *
 * <p>Place it on the service main class (the class that also carries
 * {@code @SpringBootApplication} and {@code @Import(SocpPlatformAutoConfiguration.class)}).
 * It registers exactly the packages Boot would have scanned by default — the
 * annotated class' own package — plus the platform packages that must always be
 * part of the persistence unit, through
 * {@link org.springframework.boot.autoconfigure.domain.EntityScanPackages}. Boot's
 * JPA auto-configuration then uses that list instead of the implicit
 * {@code AutoConfigurationPackages} default, so the resulting persistence unit is
 * the service's own entities plus the platform contributions.</p>
 *
 * <p><strong>What this does NOT do.</strong> It enables no JPA auditing:
 * {@code com.socp.platform.data.domain.BaseEntity} fills {@code tenantId},
 * {@code createdAt} and {@code updatedAt} from its own
 * {@code @PrePersist}/{@code @PreUpdate} callbacks, and the repository contains no
 * {@code @EnableJpaAuditing} anywhere. It also does not bind
 * {@code @ConfigurationProperties}: services keep declaring them explicitly on the
 * main class with {@code @EnableConfigurationProperties}, because a scanning
 * alternative such as {@code @ConfigurationPropertiesScan} silently registers every
 * annotated class it finds, including ones already registered as {@code @Component}
 * (see {@code docs/adding-a-service.md}).</p>
 *
 * <p>Adding {@code com.socp.platform} to a service's own {@code @EntityScan} is not
 * required to make {@code BaseEntity} work: Hibernate binds a
 * {@code @MappedSuperclass} by walking the entity's Java superclass chain, not by
 * package scanning. The platform package is registered here so a future platform
 * entity or embeddable joins every service persistence unit without editing each
 * main class; {@code SocpPlatformJpaConfigurationTest} pins both halves.</p>
 *
 * <p>Two deliberate exceptions do not use this annotation: {@code api-gateway} is
 * WebFlux and never imports the servlet starter, and {@code detect-web} owns two
 * explicit persistence units (primary detection plus secondary analysis) with their
 * own {@code EntityManagerFactory} beans, where a single global entity-scan list
 * would be wrong. {@code report-web} excludes {@code DataSource} auto-configuration
 * entirely and has no JPA.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(SocpPlatformJpaRegistrar.class)
public @interface EnableSocpPlatformJpa {

    /**
     * Extra entity packages outside the annotated class' own package, for example a
     * shared model module. Leave empty when every entity lives under the service
     * package; {@code build/verify-architecture.py} fails an unnecessary list.
     */
    String[] entityPackages() default {};
}
