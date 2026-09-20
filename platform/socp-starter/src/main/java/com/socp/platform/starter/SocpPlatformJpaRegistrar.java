package com.socp.platform.starter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.domain.EntityScanPackages;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Contributes the entity packages that {@link EnableSocpPlatformJpa} owns.
 *
 * <p>Spring Boot builds a servlet service's persistence unit from the
 * {@link EntityScanPackages} bean when one is registered and only falls back to the
 * implicit {@code AutoConfigurationPackages} root when none is, so this registrar has
 * to carry the annotated class' own package as well as the platform packages. Doing
 * it here instead of putting {@code @EntityScan} on the meta-annotation keeps the
 * merge order deterministic: {@link EntityScanPackages#register(BeanDefinitionRegistry, java.util.Collection)}
 * is additive, so a service that still declares its own {@code @EntityScan} alongside
 * this annotation ends up with the union rather than whichever annotation happened to
 * be applied last.</p>
 */
final class SocpPlatformJpaRegistrar implements ImportBeanDefinitionRegistrar {

    /**
     * Platform packages that always belong in a servlet service's persistence unit.
     * Kept as readable source data so {@code build/verify-architecture.py} can assert
     * every entry stays inside the starter's own {@code @ComponentScan} whitelist.
     */
    static final List<String> PLATFORM_ENTITY_PACKAGES = List.of(
            "com.socp.platform.data.domain");

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
            BeanDefinitionRegistry registry) {
        Set<String> packages = new LinkedHashSet<>();
        packages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
        Map<String, Object> attributes = importingClassMetadata
                .getAnnotationAttributes(EnableSocpPlatformJpa.class.getName());
        if (attributes != null && attributes.get("entityPackages") instanceof String[] declared) {
            Arrays.stream(declared).filter(StringUtils::hasText).forEach(packages::add);
        }
        packages.addAll(PLATFORM_ENTITY_PACKAGES);
        EntityScanPackages.register(registry, new ArrayList<>(packages));
    }
}
