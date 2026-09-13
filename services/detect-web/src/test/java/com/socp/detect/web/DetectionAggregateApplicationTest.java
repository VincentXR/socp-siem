package com.socp.detect.web;

import com.socp.detect.model.persistence.repository.AnalyzedRepository;
import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.Watchlists;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:detection-aggregate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.url=jdbc:h2:mem:detection-aggregate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.user=sa",
        "spring.flyway.password=",
        "socp.detect.model.datasource.url=jdbc:h2:mem:secondary-analysis-aggregate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "socp.detect.model.datasource.driver-class-name=org.h2.Driver",
        "socp.detect.model.datasource.username=sa",
        "socp.detect.model.datasource.password=",
        "socp.detect.model.flyway.url=jdbc:h2:mem:secondary-analysis-aggregate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "socp.detect.model.flyway.user=sa",
        "socp.detect.model.flyway.password=",
        "socp.kafka.enabled=false",
        "socp.health.required-endpoints=",
        "management.endpoint.health.validate-group-membership=false"
})
class DetectionAggregateApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RuleRepository rules;

    @Autowired
    private AnalyzedRepository analyzed;

    @AfterEach
    void restoreProcessLocalWatchlists() {
        TenantContext.set("aggregate-test");
        try {
            Watchlists.clear();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void bootsBothPersistenceUnitsInOneDetectionProcess() {
        assertThat(context.containsBean("entityManagerFactory")).isTrue();
        assertThat(context.containsBean("secondaryAnalysisEntityManagerFactory")).isTrue();
        assertThat(rules.countByTenantId("aggregate-test")).isZero();
        assertThat(analyzed.countByTenantId("aggregate-test")).isZero();
    }
}
