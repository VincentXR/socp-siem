package com.socp.detect.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DetectRuntimeRoleConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RoleConfigurations.class);

    @Test
    void allRoleKeepsBothSurfacesEnabled() {
        runner.run(context -> {
            assertThat(context).hasBean("apiMarker");
            assertThat(context).hasBean("workerMarker");
        });
    }

    @Test
    void apiRoleDisablesContinuousWorkerSurface() {
        runner.withPropertyValues("socp.detect.runtime-role=api").run(context -> {
            assertThat(context).hasBean("apiMarker");
            assertThat(context).doesNotHaveBean("workerMarker");
        });
    }

    @Test
    void workerRoleDisablesManagementSurface() {
        runner.withPropertyValues("socp.detect.runtime-role=worker").run(context -> {
            assertThat(context).doesNotHaveBean("apiMarker");
            assertThat(context).hasBean("workerMarker");
        });
    }

    @Test
    void invalidRoleFailsClosedWithActionableConfigurationError() {
        runner.withPropertyValues("socp.detect.runtime-role=unexpected").run(context ->
                assertThat(context.getStartupFailure())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("socp.detect.runtime-role must be one of all, api, worker"));
    }

    @Test
    void classLevelRolesAreAppliedToImportedConfigurations() {
        runner.withUserConfiguration(ClassRoleConfigurations.class)
                .withPropertyValues("socp.detect.runtime-role=api")
                .run(context -> {
                    assertThat(context).hasBean("classApiMarker");
                    assertThat(context).doesNotHaveBean("classWorkerMarker");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class RoleConfigurations {
        @Bean
        @DetectRuntimeRole(DetectRuntimeRole.Role.API)
        String apiMarker() {
            return "api";
        }

        @Bean
        @DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
        String workerMarker() {
            return "worker";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ApiSurface.class, WorkerSurface.class})
    static class ClassRoleConfigurations {
    }

    @Configuration(proxyBeanMethods = false)
    @DetectRuntimeRole(DetectRuntimeRole.Role.API)
    static class ApiSurface {
        @Bean
        String classApiMarker() {
            return "api-class";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
    static class WorkerSurface {
        @Bean
        String classWorkerMarker() {
            return "worker-class";
        }
    }
}
