package com.socp.platform.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.socp.platform.data.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScanPackages;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the assembly claims behind {@link EnableSocpPlatformJpa}.
 *
 * <ol>
 *   <li>Boot resolves a servlet service's persistence unit from the
 *   {@link EntityScanPackages} bean when one exists and only falls back to
 *   {@code AutoConfigurationPackages} when it does not. The annotation therefore has
 *   to contribute the service root package <em>and</em> the platform package.</li>
 *   <li>A {@link MappedSuperclass} in a package that is never scanned still binds its
 *   properties to the subclass, because Hibernate walks the Java superclass chain.
 *   That is why the old per-main-class comment claiming {@code @EntityScan} had to
 *   name {@code com.socp.platform} "否则基类不会被纳入持久化单元" described the
 *   mechanism wrongly, and why services no longer hand-write {@code @EntityScan}.</li>
 *   <li>{@code BaseEntity} carries its own {@code @PrePersist}/{@code @PreUpdate}
 *   callbacks, so there is nothing to unify with {@code @EnableJpaAuditing} (the
 *   repository has none).</li>
 * </ol>
 */
class SocpPlatformJpaConfigurationTest {

    @Test
    @DisplayName("平台 JPA 装配同时登记服务根包与平台实体包")
    void registersServiceRootAndPlatformPackages() {
        new ApplicationContextRunner()
                .withUserConfiguration(SampleServiceApplication.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(EntityScanPackages.get(context).getPackageNames())
                            .contains(SampleOrder.class.getPackageName(),
                                    "com.socp.platform.data.domain");
                });
    }

    @Test
    @DisplayName("显式 entityPackages 是追加，不会顶掉默认扫描根")
    void appendsDeclaredExtraEntityPackages() {
        // The extra name only has to be a string the registrar carries through:
        // nothing scans it here, because this context has no JPA auto-configuration.
        new ApplicationContextRunner()
                .withUserConfiguration(ExtendedSampleApplication.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(EntityScanPackages.get(context).getPackageNames())
                            .contains("com.socp.platform.starter",
                                    "com.socp.platform.starter.model",
                                    "com.socp.platform.data.domain");
                });
    }

    @Test
    @DisplayName("未扫描基类所在包时，@MappedSuperclass 属性仍绑定到子类")
    void bindsMappedSuperclassPropertiesWithoutScanningItsPackage() {
        // Only the subclass is registered, exactly like a service that scans its own
        // package. Hibernate walks the Java superclass chain, so the inherited
        // tenantId/createdAt/updatedAt properties are mapped anyway. No JDBC access is
        // needed to observe that, so the dialect is pinned and metadata access off.
        Metadata metadata = new MetadataSources(
                new StandardServiceRegistryBuilder()
                        .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                        .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                        .build())
                .addAnnotatedClass(SampleOrder.class)
                .buildMetadata();

        PersistentClass binding = metadata.getEntityBindings().stream()
                .filter(candidate -> SampleOrder.class.getName().equals(candidate.getClassName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "subclass was not mapped without scanning BaseEntity's package"));
        // Property names rather than column names: this raw bootstrap has no Spring Boot
        // physical naming strategy, but the mapped-superclass contribution is identical.
        assertThat(propertyNames(binding)).contains("tenantId", "createdAt", "updatedAt");
        assertThat(binding.getTable().getColumns())
                .extracting(column -> column.getName().toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains("tenant"));
    }

    @Test
    @DisplayName("BaseEntity 自带 @PrePersist/@PreUpdate，平台侧不依赖 JPA Auditing")
    void platformBaseEntityFillsItsOwnAuditFields() {
        assertThat(BaseEntity.class.isAnnotationPresent(MappedSuperclass.class)).isTrue();
        assertThat(lifecycleCallbackNames(BaseEntity.class)).contains("onCreate", "onUpdate");
    }

    private static List<String> propertyNames(PersistentClass binding) {
        List<String> names = new ArrayList<>();
        binding.getProperties().forEach(property -> names.add(property.getName()));
        return names;
    }

    private static List<String> lifecycleCallbackNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PrePersist.class) || method.isAnnotationPresent(PreUpdate.class)) {
                names.add(method.getName());
            }
        }
        return names;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSocpPlatformJpa
    static class SampleServiceApplication {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSocpPlatformJpa(entityPackages = "com.socp.platform.starter.model")
    static class ExtendedSampleApplication {
    }

    /** Test-only entity that inherits the platform audit fields. */
    @Entity
    @Table(name = "t_sample_order")
    static class SampleOrder extends BaseEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String reference;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getReference() {
            return reference;
        }

        public void setReference(String reference) {
            this.reference = reference;
        }
    }
}
