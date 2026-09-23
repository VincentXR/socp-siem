package com.socp.hips.web.service;

import com.socp.hips.web.HipsWebApplication;
import com.socp.platform.client.http.SocpHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(classes = HipsWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:hips-history-runtime;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "socp.demo-data.enabled=false",
        "socp.hips.forwarding.enabled=false", "socp.hips.history-retention.days=1",
        "socp.hips.history-retention.batch-size=2", "socp.hips.history-retention.max-batches=1",
        "socp.hips.history-retention.delay-ms=100", "socp.hips.history-retention.initial-delay-ms=100"
})
@DirtiesContext
class EndpointHistoryRetentionRuntimeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.scheduling.TaskScheduler scheduler;
    @MockitoBean SocpHttpClient http;

    @Test void schedulerCleansOldHistoryWhileForwardingIsPausedAndKeepsUnresolvedEvidence() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (String id : java.util.List.of("old-1", "old-2", "old-3", "protected")) {
                jdbc.update("insert into t_endpoint_event(event_id,tenant_id,received_at,payload_json) values(?,'tenant-a',?,'{}')", id, Timestamp.from(Instant.EPOCH));
            }
            jdbc.update("insert into t_endpoint_event(event_id,tenant_id,received_at,payload_json) values('recent','tenant-a',?,'{}')", Timestamp.from(Instant.now()));
            jdbc.update("""
                    insert into t_endpoint_forwarding(event_id,tenant_id,payload_json,status,next_attempt_at,created_at)
                    values('protected','tenant-a','{}','DEAD',?,?)
                    """, Timestamp.from(Instant.EPOCH), Timestamp.from(Instant.EPOCH));
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForList("select event_id from t_endpoint_event", String.class))
                        .containsExactlyInAnyOrder("protected", "recent"));
        assertThat(scheduler).isInstanceOf(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
        assertThat(((org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler) scheduler).getPoolSize()).isEqualTo(2);
        verifyNoInteractions(http);
    }
}
