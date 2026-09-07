package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.connector.ActionDescriptor;
import com.socp.soar.web.connector.ConnectorDescriptor;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.domain.v2.DefinitionIssue;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import com.socp.soar.web.domain.v2.SoarPlaybookVersionStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarConnectorEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Draft editing, validation, publish, deprecate and export coverage for
 * {@link SoarV2Service}.
 */
@ExtendWith(MockitoExtension.class)
class SoarV2VersionPublishCoverageTest {

    private static final String SIMPLE_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";
    private static final String EDITED_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\"},{\"id\":\"end\",\"type\":\"END\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";
    private static final String DEFAULT_RISK = "{\"highRiskActionCount\":0,\"actionCount\":0}";
    private static final String CONNECTED_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\"," +
            "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\"},"
            + "{\"id\":\"act\",\"type\":\"ACTION\",\"actionRef\":\"my.conn/run@1\",\"connectionRef\":\"conn-1\","
            + "\"parameters\":{},\"target\":{}},"
            + "{\"id\":\"end\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"act\"},{\"from\":\"act\",\"to\":\"end\"}]}";
    private static final String SUB_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\"},"
            + "{\"id\":\"sp\",\"type\":\"SUB_PLAYBOOK\",\"playbookVersionId\":\"ver-2\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"sp\"},{\"from\":\"sp\",\"to\":\"end\"}]}";
    private static final String CYCLE_DEFINITION = SUB_DEFINITION.replace("ver-2", "ver-1");

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private SoarPlaybookRepository playbooks;
    @Mock
    private PlaybookVersionRepository versions;
    @Mock
    private SoarRunRepository runs;
    @Mock
    private SoarDispatchOutboxRepository dispatches;
    @Mock
    private SoarNodeRunRepository nodes;
    @Mock
    private SoarRunEventRepository events;
    @Mock
    private SoarApprovalRepository approvals;
    @Mock
    private SoarDefinitionValidator validator;
    @Mock
    private TemporalExecutor temporal;
    @Mock
    private SoarActionAttemptRepository attempts;
    @Mock
    private SoarManualTaskRepository manualTasks;
    @Mock
    private SoarSignalOutboxRepository signals;
    @Mock
    private SoarConnectorRepository connectors;
    @Mock
    private SoarConnectorRegistry connectorRegistry;

    private SoarV2Service service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        SoarTestIdentity.setOperator();
        service = new SoarV2Service(playbooks, versions, runs, dispatches, nodes, events, approvals,
                validator, mapper, temporal, attempts, manualTasks, signals, connectors, connectorRegistry);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SoarTestIdentity.clear();
    }

    // -------------------------------------------------------- version lookup

    @Test
    void getVersionReturnsTenantScopedView() {
        PlaybookVersionEntity draft = version("ver-2", "pb-1", 2, SoarPlaybookVersionStatus.DRAFT);
        draft.setRowVersion(3L);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "Contain host")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 2)).willReturn(Optional.of(draft));

        Map<String, Object> result = service.getVersion("pb-1", 2);

        assertThat(result).containsEntry("id", "ver-2")
                .containsEntry("playbookId", "pb-1")
                .containsEntry("version", 2)
                .containsEntry("status", SoarPlaybookVersionStatus.DRAFT.name())
                .containsEntry("playbookStatus", "ACTIVE")
                .containsEntry("rowVersion", 3L);
        assertThat(((JsonNode) result.get("definition")).path("entryNodeId").asText()).isEqualTo("start");
        verify(versions).findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 2);
    }

    @Test
    void getVersionRejectsUnknownPlaybook() {
        assertThatThrownBy(() -> service.getVersion("pb-missing", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(404);
                    assertThat(error.getReason()).contains("SOAR_PLAYBOOK_NOT_FOUND");
                });
    }

    @Test
    void getVersionRejectsUnknownVersion() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 7)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVersion("pb-1", 7))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(404);
                    assertThat(error.getReason()).contains("SOAR_VERSION_NOT_FOUND");
                });
    }

    // -------------------------------------------------------------- saveDraft

    @Test
    void saveDraftPersistsDefinitionHashAndRiskSummary() throws Exception {
        PlaybookVersionEntity draft = draft();
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "Contain host")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));
        given(validator.validate(EDITED_DEFINITION)).willReturn(validation(true, "draft-hash", 2, 3, 1));

        Map<String, Object> result = service.saveDraft("pb-1", 1, EDITED_DEFINITION, "{\"layout\":1}", null);

        assertThat(draft.getDefinitionJson()).isEqualTo(EDITED_DEFINITION);
        assertThat(draft.getLayoutJson()).isEqualTo("{\"layout\":1}");
        assertThat(draft.getDefinitionHash()).isEqualTo("draft-hash");
        assertThat(draft.getSchemaVersion()).isEqualTo(SoarDefinitionValidator.SCHEMA_VERSION);
        JsonNode risk = mapper.readTree(draft.getRiskSummaryJson());
        assertThat(risk.path("actionCount").asInt()).isEqualTo(3);
        assertThat(risk.path("highRiskActionCount").asInt()).isEqualTo(1);
        assertThat(risk.path("valid").asBoolean()).isTrue();
        assertThat(result).containsEntry("version", 1)
                .containsEntry("status", SoarPlaybookVersionStatus.DRAFT.name())
                .containsEntry("definitionHash", "draft-hash");
        verify(versions).save(draft);
    }

    @Test
    void saveDraftFallsBackToCanonicalHashAndDefaultSchema() {
        PlaybookVersionEntity draft = draft();
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));
        given(validator.validate(EDITED_DEFINITION)).willReturn(new DefinitionValidationResult(true,
                List.of(), List.of(), null, null, 0, 0, 0));
        given(validator.canonicalHash(EDITED_DEFINITION)).willReturn("fallback-hash");

        service.saveDraft("pb-1", 1, EDITED_DEFINITION, null, null);

        assertThat(draft.getDefinitionHash()).isEqualTo("fallback-hash");
        assertThat(draft.getSchemaVersion()).isEqualTo(SoarDefinitionValidator.SCHEMA_VERSION);
        assertThat(draft.getLayoutJson()).isEqualTo("{}");
    }

    @Test
    void saveDraftRejectsImmutableVersion() {
        PlaybookVersionEntity published = version("ver-1", "pb-1", 1, SoarPlaybookVersionStatus.PUBLISHED);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1))
                .willReturn(Optional.of(published));

        assertThatThrownBy(() -> service.saveDraft("pb-1", 1, EDITED_DEFINITION, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).contains("SOAR_VERSION_IMMUTABLE");
                });
        verify(versions, never()).save(any(PlaybookVersionEntity.class));
    }

    @Test
    void saveDraftRejectsStaleRowVersion() {
        PlaybookVersionEntity draft = draft();
        draft.setRowVersion(2L);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.saveDraft("pb-1", 1, EDITED_DEFINITION, null, 7L))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).contains("SOAR_VERSION_CONFLICT");
                });
        assertThat(draft.getDefinitionJson()).isEqualTo(SIMPLE_DEFINITION);
    }

    @Test
    void saveDraftRejectsBlankDefinition() {
        PlaybookVersionEntity draft = draft();
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.saveDraft("pb-1", 1, "   ", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(400);
                    assertThat(error.getReason()).contains("SOAR_DEFINITION_INVALID");
                });
        verify(validator, never()).validate(anyString());
    }

    @Test
    void saveDraftRejectsInlineSecrets() {
        PlaybookVersionEntity draft = draft();
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));
        given(validator.validate(EDITED_DEFINITION)).willReturn(new DefinitionValidationResult(false,
                List.of(DefinitionIssue.error("DEFINITION_SECRET_INLINE_FORBIDDEN", "act", "$.nodes[1]",
                                "inline secret is not allowed"),
                        DefinitionIssue.error("ACTION_SECRET_INLINE_FORBIDDEN", "act", "$.nodes[1].input",
                                "use a secretRef instead")),
                List.of(), SoarDefinitionValidator.SCHEMA_VERSION, "secret-hash", 2, 1, 1));

        assertThatThrownBy(() -> service.saveDraft("pb-1", 1, EDITED_DEFINITION, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(400);
                    assertThat(error.getReason()).contains("SOAR_SECRET_INLINE_FORBIDDEN");
                });
        assertThat(draft.getDefinitionJson()).isEqualTo(SIMPLE_DEFINITION);
        verify(versions, never()).save(any(PlaybookVersionEntity.class));
    }

    @Test
    void saveDraftRejectsUnknownPlaybook() {
        assertThatThrownBy(() -> service.saveDraft("pb-missing", 1, EDITED_DEFINITION, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(404);
                    assertThat(error.getReason()).contains("SOAR_PLAYBOOK_NOT_FOUND");
                });
    }

    @Test
    void saveDraftRejectsUnknownVersion() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 5)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveDraft("pb-1", 5, EDITED_DEFINITION, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(404);
                    assertThat(error.getReason()).contains("SOAR_VERSION_NOT_FOUND");
                });
    }

    // -------------------------------------------------------- validateVersion

    @Test
    void validateVersionReturnsValidatorResult() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1))
                .willReturn(Optional.of(version("ver-1", "pb-1", 1, SoarPlaybookVersionStatus.DRAFT)));
        given(validator.validate(SIMPLE_DEFINITION)).willReturn(validation(true, "validate-hash", 2, 1, 0));

        DefinitionValidationResult result = service.validateVersion("pb-1", 1);

        assertThat(result.valid()).isTrue();
        assertThat(result.definitionHash()).isEqualTo("validate-hash");
        assertThat(result.schemaVersion()).isEqualTo(SoarDefinitionValidator.SCHEMA_VERSION);
        assertThat(result.nodeCount()).isEqualTo(2);
        assertThat(result.actionCount()).isEqualTo(1);
        assertThat(result.highRiskActionCount()).isZero();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        verify(validator).validate(SIMPLE_DEFINITION);
    }

    @Test
    void validateVersionSurfacesErrorsAndWarnings() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 2))
                .willReturn(Optional.of(version("ver-2", "pb-1", 2, SoarPlaybookVersionStatus.DRAFT,
                        SIMPLE_DEFINITION, "{\"highRiskActionCount\":1,\"actionCount\":2}")));
        given(validator.validate(SIMPLE_DEFINITION)).willReturn(new DefinitionValidationResult(false,
                List.of(DefinitionIssue.error("DEFINITION_ENTRY_MISSING", null, "$.entryNodeId", "entry missing"),
                        DefinitionIssue.error("DEFINITION_EDGE_INVALID", "n1", "$.edges[0]", "edge invalid")),
                List.of(DefinitionIssue.warning("DEFINITION_UNUSED_NODE", "n9", "$.nodes[9]", "unused node")),
                SoarDefinitionValidator.SCHEMA_VERSION, "invalid-hash", 3, 2, 1));

        DefinitionValidationResult result = service.validateVersion("pb-1", 2);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors().get(0).code()).isEqualTo("DEFINITION_ENTRY_MISSING");
        assertThat(result.errors().get(0).severity()).isEqualTo("ERROR");
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0).code()).isEqualTo("DEFINITION_UNUSED_NODE");
        assertThat(result.warnings().get(0).severity()).isEqualTo("WARNING");
        assertThat(result.nodeCount()).isEqualTo(3);
        assertThat(result.highRiskActionCount()).isEqualTo(1);
    }

    // --------------------------------------------------------------- publish

    @Test
    void publishMarksVersionPublishedAndAdvancesPlaybook() throws Exception {
        SoarPlaybookEntity playbook = playbook("pb-1", "Contain host");
        PlaybookVersionEntity draft = draft();
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));
        given(validator.validate(SIMPLE_DEFINITION)).willReturn(validation(true, "published-hash", 2, 2, 1));

        Map<String, Object> result = service.publish("pb-1", 1);

        assertThat(draft.getStatus()).isEqualTo(SoarPlaybookVersionStatus.PUBLISHED.name());
        assertThat(draft.getPublishedBy()).isEqualTo("operator");
        assertThat(draft.getPublishedAt()).isNotNull();
        assertThat(draft.getDefinitionHash()).isEqualTo("published-hash");
        JsonNode risk = mapper.readTree(draft.getRiskSummaryJson());
        assertThat(risk.path("actionCount").asInt()).isEqualTo(2);
        assertThat(risk.path("highRiskActionCount").asInt()).isEqualTo(1);
        assertThat(risk.path("valid").asBoolean()).isTrue();
        assertThat(playbook.getLatestPublishedVersion()).isEqualTo(1);
        assertThat(result).containsEntry("status", SoarPlaybookVersionStatus.PUBLISHED.name())
                .containsEntry("publishedBy", "operator")
                .containsEntry("playbookStatus", "ACTIVE");
        assertThat(result.get("publishedAt")).isNotNull();
        verify(versions).save(draft);
        verify(playbooks).save(playbook);
    }

    @Test
    void publishRejectsInvalidDefinition() {
        PlaybookVersionEntity draft = draft();
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));
        given(validator.validate(SIMPLE_DEFINITION)).willReturn(new DefinitionValidationResult(false,
                List.of(DefinitionIssue.error("DEFINITION_ENTRY_MISSING", null, "$.entryNodeId", "entry missing"),
                        DefinitionIssue.error("DEFINITION_NODE_INVALID", "n1", "$.nodes[0]", "node invalid")),
                List.of(), SoarDefinitionValidator.SCHEMA_VERSION, "bad-hash", 2, 1, 1));

        assertThatThrownBy(() -> service.publish("pb-1", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(400);
                    assertThat(error.getReason()).contains("SOAR_DEFINITION_INVALID");
                });
        assertThat(draft.getStatus()).isEqualTo(SoarPlaybookVersionStatus.DRAFT.name());
        verify(versions, never()).save(any(PlaybookVersionEntity.class));
    }

    @Test
    void publishRejectsImmutableVersion() {
        PlaybookVersionEntity published = version("ver-1", "pb-1", 1, SoarPlaybookVersionStatus.PUBLISHED);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1))
                .willReturn(Optional.of(published));

        assertThatThrownBy(() -> service.publish("pb-1", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).contains("SOAR_VERSION_IMMUTABLE");
                });
    }

    @Test
    void publishRejectsUnknownVersion() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 4)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish("pb-1", 4))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(404);
                    assertThat(error.getReason()).contains("SOAR_VERSION_NOT_FOUND");
                });
    }

    @Test
    void publishIncludesEmptyConnectionHealthWithoutConnectors() {
        SoarPlaybookEntity playbook = playbook("pb-1", "Contain host");
        PlaybookVersionEntity draft = draft();
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));
        given(validator.validate(SIMPLE_DEFINITION)).willReturn(validation(true, "published-hash", 2, 2, 1));

        Map<String, Object> result = service.publish("pb-1", 1);

        assertThat(result).containsEntry("status", SoarPlaybookVersionStatus.PUBLISHED.name());
        assertThat(result.get("connectionHealth")).isEqualTo(List.of());
    }

    @Test
    void publishRejectsMissingSubPlaybookVersionBeforeChangingDraft() {
        PlaybookVersionEntity draft = draft();
        draft.setDefinitionJson(SUB_DEFINITION);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));
        given(validator.validate(SUB_DEFINITION)).willReturn(validation(true, "sub-hash", 3, 0, 0));
        given(versions.findByTenantIdAndId("tenant-a", "ver-2")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish("pb-1", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).contains("SOAR_SUB_PLAYBOOK_NOT_FOUND"));
        assertThat(draft.getStatus()).isEqualTo(SoarPlaybookVersionStatus.DRAFT.name());
        verify(versions, never()).save(any(PlaybookVersionEntity.class));
    }

    @Test
    void publishRejectsUnpublishedSubPlaybookVersion() {
        PlaybookVersionEntity draft = draft();
        draft.setDefinitionJson(SUB_DEFINITION);
        PlaybookVersionEntity child = version("ver-2", "pb-2", 1, SoarPlaybookVersionStatus.DRAFT);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));
        given(validator.validate(SUB_DEFINITION)).willReturn(validation(true, "sub-hash", 3, 0, 0));
        given(versions.findByTenantIdAndId("tenant-a", "ver-2")).willReturn(Optional.of(child));

        assertThatThrownBy(() -> service.publish("pb-1", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).contains("SOAR_SUB_PLAYBOOK_NOT_PUBLISHED"));
        verify(versions, never()).save(any(PlaybookVersionEntity.class));
    }

    @Test
    void publishRejectsRecursiveSubPlaybookGraph() {
        PlaybookVersionEntity draft = draft();
        draft.setDefinitionJson(SUB_DEFINITION);
        PlaybookVersionEntity child = version("ver-2", "pb-2", 1, SoarPlaybookVersionStatus.PUBLISHED,
                CYCLE_DEFINITION, DEFAULT_RISK);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));
        given(validator.validate(SUB_DEFINITION)).willReturn(validation(true, "sub-hash", 3, 0, 0));
        given(versions.findByTenantIdAndId("tenant-a", "ver-2")).willReturn(Optional.of(child));

        assertThatThrownBy(() -> service.publish("pb-1", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).contains("SOAR_SUB_PLAYBOOK_CYCLE"));
        verify(versions, never()).save(any(PlaybookVersionEntity.class));
    }

    @Test
    void publishConnectionHealthSurfacesEnabledConnectorState() {
        SoarPlaybookEntity playbook = playbook("pb-1", "Contain host");
        PlaybookVersionEntity draft = draft();
        draft.setDefinitionJson(CONNECTED_DEFINITION);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1)).willReturn(Optional.of(draft));
        given(validator.validate(CONNECTED_DEFINITION)).willReturn(validation(true, "connected-hash", 3, 1, 0));

        ActionDescriptor action = new ActionDescriptor("run", 1, "Run", "", "LOW", "NONE", "NONE",
                false, List.of("host"), Map.of(), Map.of());
        given(connectorRegistry.descriptorForAction("my.conn/run@1"))
                .willReturn(Optional.of(new ConnectorDescriptor("my.conn", 1, "My Connector", true, List.of(action))));
        given(connectorRegistry.canonicalActionRef(anyString())).willAnswer(invocation -> invocation.getArgument(0));

        SoarConnectorEntity row = new SoarConnectorEntity();
        row.setId("conn-1");
        row.setName("FW");
        row.setConnectorType("my.conn");
        row.setEnabled(true);
        row.setStatus("HEALTHY");
        row.setLastTestAt(Instant.parse("2026-09-01T00:00:00Z"));
        row.setLastTestStatus("OK");
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));

        Map<String, Object> result = service.publish("pb-1", 1);

        List<?> health = (List<?>) result.get("connectionHealth");
        assertThat(health).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) health.get(0);
        assertThat(entry).containsEntry("connectionRef", "conn-1")
                .containsEntry("connectorType", "my.conn")
                .containsEntry("ready", true)
                .containsEntry("status", "HEALTHY")
                .containsEntry("lastTestStatus", "OK");
    }

    // ------------------------------------------------------------- deprecate

    @Test
    void deprecateMarksVersionDeprecatedAndPromotesReplacement() {
        SoarPlaybookEntity playbook = playbook("pb-1", "Contain host");
        playbook.setLatestPublishedVersion(1);
        PlaybookVersionEntity current = version("ver-1", "pb-1", 1, SoarPlaybookVersionStatus.PUBLISHED);
        PlaybookVersionEntity replacement = version("ver-2", "pb-1", 2, SoarPlaybookVersionStatus.PUBLISHED);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1))
                .willReturn(Optional.of(current));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-1"))
                .willReturn(List.of(replacement, current));

        Map<String, Object> result = service.deprecate("pb-1", 1);

        assertThat(current.getStatus()).isEqualTo(SoarPlaybookVersionStatus.DEPRECATED.name());
        assertThat(playbook.getLatestPublishedVersion()).isEqualTo(2);
        assertThat(result).containsEntry("version", 1)
                .containsEntry("status", SoarPlaybookVersionStatus.DEPRECATED.name());
        verify(versions).save(current);
        verify(playbooks).save(playbook);
    }

    @Test
    void deprecateClearsLatestPublishedWhenNoReplacementExists() {
        SoarPlaybookEntity playbook = playbook("pb-1", "Contain host");
        playbook.setLatestPublishedVersion(1);
        PlaybookVersionEntity current = version("ver-1", "pb-1", 1, SoarPlaybookVersionStatus.PUBLISHED);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1))
                .willReturn(Optional.of(current));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-1"))
                .willReturn(List.of(current));

        service.deprecate("pb-1", 1);

        assertThat(current.getStatus()).isEqualTo(SoarPlaybookVersionStatus.DEPRECATED.name());
        assertThat(playbook.getLatestPublishedVersion()).isNull();
    }

    @Test
    void deprecateRejectsDraftVersion() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 2))
                .willReturn(Optional.of(version("ver-2", "pb-1", 2, SoarPlaybookVersionStatus.DRAFT)));

        assertThatThrownBy(() -> service.deprecate("pb-1", 2))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).contains("SOAR_VERSION_IMMUTABLE");
                });
    }

    @Test
    void deprecateRejectsUnknownVersion() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 3)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deprecate("pb-1", 3))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(404);
                    assertThat(error.getReason()).contains("SOAR_VERSION_NOT_FOUND");
                });
    }

    // ---------------------------------------------------------------- export

    @Test
    void exportVersionAddsFormatAndTimestamp() {
        PlaybookVersionEntity published = version("ver-1", "pb-1", 1, SoarPlaybookVersionStatus.PUBLISHED);
        published.setPublishedBy("operator");
        published.setPublishedAt(Instant.now());
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "Contain host")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 1))
                .willReturn(Optional.of(published));

        Map<String, Object> exported = service.exportVersion("pb-1", 1);

        assertThat(exported).containsEntry("format", "soar.playbook/v2")
                .containsEntry("id", "ver-1")
                .containsEntry("playbookId", "pb-1")
                .containsEntry("version", 1)
                .containsEntry("status", SoarPlaybookVersionStatus.PUBLISHED.name())
                .containsEntry("schemaVersion", SoarDefinitionValidator.SCHEMA_VERSION)
                .containsEntry("playbookStatus", "ACTIVE");
        assertThat(exported.get("exportedAt")).isInstanceOf(Instant.class);
        assertThat(((JsonNode) exported.get("definition")).path("entryNodeId").asText()).isEqualTo("start");
        assertThat(((JsonNode) exported.get("riskSummary")).path("actionCount").asInt()).isZero();
    }

    // ------------------------------------------------------------- fixtures

    private PlaybookVersionEntity draft() {
        return version("ver-1", "pb-1", 1, SoarPlaybookVersionStatus.DRAFT);
    }

    private PlaybookVersionEntity version(String id, String playbookId, int versionNo,
                                          SoarPlaybookVersionStatus status) {
        return version(id, playbookId, versionNo, status, SIMPLE_DEFINITION, DEFAULT_RISK);
    }

    private PlaybookVersionEntity version(String id, String playbookId, int versionNo,
                                          SoarPlaybookVersionStatus status, String definition,
                                          String riskSummary) {
        PlaybookVersionEntity entity = new PlaybookVersionEntity();
        entity.setId(id);
        entity.setTenantId("tenant-a");
        entity.setPlaybookId(playbookId);
        entity.setVersionNo(versionNo);
        entity.setStatus(status.name());
        entity.setSchemaVersion(SoarDefinitionValidator.SCHEMA_VERSION);
        entity.setDefinitionJson(definition);
        entity.setLayoutJson("{}");
        entity.setDefinitionHash("hash-" + versionNo);
        entity.setRiskSummaryJson(riskSummary);
        entity.setCreatedBy("operator");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setRowVersion(1L);
        return entity;
    }

    private SoarPlaybookEntity playbook(String id, String name) {
        SoarPlaybookEntity entity = new SoarPlaybookEntity();
        entity.setId(id);
        entity.setTenantId("tenant-a");
        entity.setName(name);
        entity.setOwner("operator");
        entity.setStatus("ACTIVE");
        entity.setTagsJson("[]");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    private static DefinitionValidationResult validation(boolean valid, String hash, int nodes,
                                                         int actions, int highRisk) {
        return new DefinitionValidationResult(valid,
                valid ? List.of() : List.of(DefinitionIssue.error("DEFINITION_INVALID", null, "", "bad graph")),
                List.of(DefinitionIssue.warning("DEFINITION_HINT", null, "", "hint")),
                SoarDefinitionValidator.SCHEMA_VERSION, hash, nodes, actions, highRisk);
    }
}
