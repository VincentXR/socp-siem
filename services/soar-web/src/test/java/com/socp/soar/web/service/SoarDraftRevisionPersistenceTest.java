package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each command commits independently, just as successive browser HTTP writes do. */
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SoarDraftRevisionPersistenceTest {
    private static final String DEFINITION = """
            {"schemaVersion":"soar.playbook","entryNodeId":"start","nodes":[
            {"id":"start","type":"START","name":"Start"},
            {"id":"done","type":"END","outcome":"SUCCEEDED"}],
            "edges":[{"from":"start","to":"done"}]}
            """;
    @Autowired SoarPlaybookRepository playbooks;
    @Autowired PlaybookVersionRepository versions;
    @Autowired PlatformTransactionManager transactionManager;
    private SoarPlaybookCommandService commands;
    private TransactionTemplate transaction;

    @BeforeEach void setup() {
        TenantContext.set("draft-revision-" + UUID.randomUUID());
        var mapper = new ObjectMapper();
        var json = new SoarJson(mapper);
        commands = new SoarPlaybookCommandService(playbooks, versions, new SoarDefinitionValidator(mapper),
                mapper, new SoarReadModelMapper(mapper), json, new SoarRecords(playbooks, versions, null),
                new SoarRuntimeGate(), new SoarDefinitionPolicy(playbooks, versions, mapper, null, null, json));
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void returnedRevisionSupportsSuccessiveSavesButStillRejectsStaleEdits() {
        Map<String, Object> created = transaction.execute(status -> commands.createPlaybook("Revision test", "", List.of()));
        String id = (String) created.get("id");
        Long original = revision(id);
        Map<String, Object> first = transaction.execute(status -> commands.saveDraft(id, 1, DEFINITION, "{}", original));
        assertThat(first.get("rowVersion")).isEqualTo(revision(id)).isNotEqualTo(original);
        Map<String, Object> second = transaction.execute(status -> commands.saveDraft(id, 1,
                DEFINITION.replace("Start", "Reviewed"), "{}", (Long) first.get("rowVersion")));
        assertThat(second.get("rowVersion")).isEqualTo(revision(id)).isNotEqualTo(first.get("rowVersion"));
        assertThatThrownBy(() -> transaction.execute(status -> commands.saveDraft(id, 1, DEFINITION, "{}", original)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("SOAR_VERSION_CONFLICT");
        assertThat(versions.findByTenantIdAndPlaybookIdAndVersionNo(TenantContext.require(), id, 1).orElseThrow()
                .getDefinitionJson()).contains("Reviewed");
        Map<String, Object> published = transaction.execute(status -> commands.publish(id, 1));
        assertThat(published.get("rowVersion")).isEqualTo(revision(id));
        Map<String, Object> deprecated = transaction.execute(status -> commands.deprecate(id, 1));
        assertThat(deprecated.get("rowVersion")).isEqualTo(revision(id));
    }

    private Long revision(String id) {
        return versions.findByTenantIdAndPlaybookIdAndVersionNo(TenantContext.require(), id, 1).orElseThrow().getRowVersion();
    }
}
