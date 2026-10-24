package com.socp.search.config.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchIndexPolicyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PolicyConfiguration.class);

    @Test
    void productionProfileDefaultsToOneReplica() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OpenSearchProperties.class).getIndexReplicas()).isEqualTo(1);
            assertThat(context).hasSingleBean(ProductionOpenSearchIndexGuard.class);
        });
    }

    @Test
    void productionRejectsAnExplicitUnreplicatedWriter() {
        runner.withPropertyValues("spring.profiles.active=prod", "socp.opensearch.index-replicas=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage(
                                "socp.opensearch.index-replicas must be at least 1 in production; "
                                        + "provision enough OpenSearch data nodes for every replica"));
    }

    @Test
    void productionBindsDeploymentTopologyOverrides() {
        runner.withPropertyValues("spring.profiles.active=prod", "socp.opensearch.index-replicas=2",
                        "socp.opensearch.index-shards=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OpenSearchProperties.class).getIndexReplicas()).isEqualTo(2);
                    assertThat(context.getBean(OpenSearchProperties.class).getIndexShards()).isEqualTo(3);
                });
    }

    @Test
    void singleNodeDevelopmentDoesNotRequireReplicas() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OpenSearchProperties.class).getIndexReplicas()).isZero();
            assertThat(context).doesNotHaveBean(ProductionOpenSearchIndexGuard.class);
        });
    }

    @Test
    void disabledOpenSearchDoesNotRequireAnIndexReplicaPolicy() {
        runner.withPropertyValues("spring.profiles.active=prod", "socp.opensearch.enabled=false",
                        "socp.opensearch.index-replicas=0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void invalidShardAndReplicaCountsFailDuringBinding() {
        for (String value : new String[]{"socp.opensearch.index-shards=0",
                "socp.opensearch.index-shards=1025", "socp.opensearch.index-replicas=-1",
                "socp.opensearch.index-replicas=33"}) {
            runner.withPropertyValues("spring.profiles.active=dev", value)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OpenSearchProperties.class)
    @Import(ProductionOpenSearchIndexGuard.class)
    static class PolicyConfiguration {
    }
}
