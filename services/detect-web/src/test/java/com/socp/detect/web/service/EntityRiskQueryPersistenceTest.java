package com.socp.detect.web.service;

import com.socp.detect.web.persistence.repository.EntityRiskAlertRepository;
import com.socp.detect.web.persistence.repository.EntityRiskProfileRepository;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class EntityRiskQueryPersistenceTest {
    @Autowired EntityRiskProfileRepository profiles;
    @Autowired EntityRiskAlertRepository alerts;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory factory;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void ranksAllTenantProfilesBeforeLimitingAndAggregatesWithoutLoadingEntities() {
        Instant now = Instant.now();
        for (int i = 0; i < 501; i++) insert("tenant-a", "old-" + i, 100, now.minusSeconds(86400));
        insert("tenant-a", "fresh", 20, now);
        insert("tenant-a", "tie", 20, now);
        insert("tenant-b", "foreign", 100, now);
        TenantContext.set("tenant-a");
        var store = new EntityRiskStore(profiles, alerts, new EntityRiskCounterStore(jdbc));
        var statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(store.top(1)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("entity", "fresh").containsEntry("risk", 20.0));
        assertThat(statistics.getEntityLoadCount()).isEqualTo(1);
        assertThat(profiles.topAt("tenant-a", now.getEpochSecond(), 2))
                .extracting(profile -> profile.getEntity()).containsExactly("fresh", "tie");
        statistics.clear();
        assertThat(store.summary()).containsEntry("entities", 503L).containsEntry("maxRisk", 20.0);
        assertThat(statistics.getEntityLoadCount()).isZero();
        var summary = profiles.summarizeAt("tenant-a", now.getEpochSecond());
        assertThat(summary.getInfo()).isEqualTo(501);
        assertThat(summary.getLow()).isEqualTo(2);
        assertThat(summary.getCritical()).isZero();
    }

    @Test void handlesEmptyTenantsAncientScoresAndPublicLevelRounding() {
        Instant now = Instant.now();
        insert("tenant-a", "ancient", 100, Instant.EPOCH);
        insert("tenant-a", "tiny", Double.MIN_VALUE, now.minusSeconds(21600));
        insert("tenant-a", "future", 85, now.plusSeconds(3600));
        // Keep the near-threshold cases stable when the real-clock service
        // query follows a slow CI scheduling pause. Decay is tested above.
        insert("tenant-a", "low", 19.46, now.plusSeconds(3600));
        insert("tenant-a", "medium", 39.46, now.plusSeconds(3600));
        insert("tenant-a", "high", 64.46, now.plusSeconds(3600));
        insert("tenant-a", "critical", 84.46, now.plusSeconds(3600));
        TenantContext.set("tenant-a");
        var store = new EntityRiskStore(profiles, alerts, new EntityRiskCounterStore(jdbc));
        var summary = profiles.summarizeAt("tenant-a", now.getEpochSecond());
        assertThat(summary.getEntities()).isEqualTo(7);
        assertThat(summary.getMaximum()).isEqualTo(85);
        assertThat(summary.getInfo()).isEqualTo(2);
        assertThat(summary.getLow()).isEqualTo(1);
        assertThat(summary.getMedium()).isEqualTo(1);
        assertThat(summary.getHigh()).isEqualTo(1);
        assertThat(summary.getCritical()).isEqualTo(2);
        assertThat(store.top(500)).filteredOn(row -> row.get("entity").equals("medium"))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("level", "MEDIUM"));
        TenantContext.set("empty");
        assertThat(store.top(10)).isEmpty();
        assertThat(store.summary()).containsEntry("entities", 0L).containsEntry("maxRisk", 0.0)
                .containsEntry("byLevel", Map.of("CRITICAL", 0L, "HIGH", 0L, "MEDIUM", 0L, "LOW", 0L, "INFO", 0L));
        TenantContext.clear();
        assertThatThrownBy(store::summary).isInstanceOf(IllegalStateException.class);
    }

    private void insert(String tenant, String entity, double score, Instant at) {
        jdbc.update("""
                insert into t_entity_risk_profile
                (entity_value,tenant_id,entity_key,score,score_at,alert_count,first_seen,last_seen,max_severity,mitre_json,rules_json,row_version)
                values (?,?,?,?,?,1,?,?,'HIGH','{}','{}',0)
                """, UUID.randomUUID().toString(), tenant, entity, score, Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
    }
}
