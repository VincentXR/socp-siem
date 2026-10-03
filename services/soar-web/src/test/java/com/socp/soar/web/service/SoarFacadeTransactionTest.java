package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** Real Spring proxy + H2 transaction rollback, with fault injection only at the failing boundary. */
@DataJpaTest(showSql = false)
@Import({SoarService.class, SoarFacadeTransactionTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SoarFacadeTransactionTest {
    @Autowired SoarService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean TemporalExecutor temporal;
    @MockitoBean SoarConnectorRegistry connectors;
    @MockitoBean SoarRunEventRepository events;
    private String tenant;
    @TestConfiguration static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean SoarDefinitionValidator validator(ObjectMapper mapper) { return new SoarDefinitionValidator(mapper); }
    }
    @BeforeEach void tenant() {
        tenant = "tx-" + UUID.randomUUID(); TenantContext.set(tenant);
        assertThat(AopUtils.isAopProxy(service)).isTrue();
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void failedImportRollsBackCreatedMetadataAndDraft() throws Exception {
        var definition = mapper.readTree("{\"schemaVersion\":\"soar.playbook\",\"entryNodeId\":\"start\","
                + "\"password\":\"inline-secret\",\"nodes\":[{\"id\":\"start\",\"type\":\"START\"},{\"id\":\"end\",\"type\":\"END\"}],\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}");
        assertThatThrownBy(() -> service.importDraft("bad import", "", List.of(), definition, null))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("SOAR_SECRET_INLINE_FORBIDDEN");
        assertThat(count("t_soar_playbook")).isZero();
        assertThat(count("t_soar_playbook_version")).isZero();
    }

    @Test void eventFailureRollsBackRunAndDispatchTogether() {
        var created = service.createPlaybook("transaction fixture", "", List.of());
        String id = String.valueOf(created.get("id"));
        service.publish(id, 1);
        String version = jdbc.queryForObject("select id from t_soar_playbook_version where tenant_id=? and playbook_id=?", String.class, tenant, id);
        doThrow(new IllegalStateException("event insert fault")).when(events).save(any(SoarRunEventEntity.class));
        assertThatThrownBy(() -> service.queueManualRun("request-one", version, Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("event insert fault");
        assertThat(count("t_soar_run")).isZero();
        assertThat(count("t_soar_dispatch_outbox")).isZero();
        assertThat(count("t_soar_playbook")).isEqualTo(1);
    }
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Integer.class, tenant);
    }
}
