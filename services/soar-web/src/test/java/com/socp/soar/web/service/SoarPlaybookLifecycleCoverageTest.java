package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.domain.DefinitionIssue;
import com.socp.soar.web.domain.DefinitionValidationResult;
import com.socp.soar.web.domain.SoarPlaybookVersionStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Playbook/version lifecycle coverage for {@link SoarService}: draft seeding on
 * create, import validation, filtered listing, metadata updates and draft cloning.
 */
@ExtendWith(MockitoExtension.class)
class SoarPlaybookLifecycleCoverageTest {

    private static final String SIMPLE_DEFINITION = "{\"schemaVersion\":\"soar.playbook\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";
    private static final String DEFAULT_RISK = "{\"highRiskActionCount\":0,\"actionCount\":0}";

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

    private SoarService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        SoarTestIdentity.setOperator();
        service = new SoarService(playbooks, versions, runs, dispatches, nodes, events, approvals,
                validator, mapper, temporal, attempts, manualTasks, signals, connectors, connectorRegistry);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SoarTestIdentity.clear();
    }

    // ---------------------------------------------------------------- create

    @Test
    void createPlaybookSeedsTenantScopedDraftVersion() {
        given(validator.canonicalHash(anyString())).willReturn("seed-hash");

        Map<String, Object> view = service.createPlaybook("Contain host", "Block the host", List.of("edr"));

        ArgumentCaptor<SoarPlaybookEntity> playbookCaptor = ArgumentCaptor.forClass(SoarPlaybookEntity.class);
        verify(playbooks).save(playbookCaptor.capture());
        SoarPlaybookEntity playbook = playbookCaptor.getValue();
        assertThat(playbook.getTenantId()).isEqualTo("tenant-a");
        assertThat(playbook.getName()).isEqualTo("Contain host");
        assertThat(playbook.getOwner()).isEqualTo("operator");
        assertThat(playbook.getStatus()).isEqualTo("ACTIVE");
        assertThat(playbook.getTagsJson()).isEqualTo("[\"edr\"]");

        ArgumentCaptor<PlaybookVersionEntity> draftCaptor = ArgumentCaptor.forClass(PlaybookVersionEntity.class);
        verify(versions).save(draftCaptor.capture());
        PlaybookVersionEntity draft = draftCaptor.getValue();
        assertThat(draft.getTenantId()).isEqualTo("tenant-a");
        assertThat(draft.getPlaybookId()).isEqualTo(playbook.getId());
        assertThat(draft.getVersionNo()).isEqualTo(1);
        assertThat(draft.getStatus()).isEqualTo(SoarPlaybookVersionStatus.DRAFT.name());
        assertThat(draft.getSchemaVersion()).isEqualTo(SoarDefinitionValidator.SCHEMA_VERSION);
        assertThat(draft.getDefinitionJson()).contains("soar.playbook");
        assertThat(draft.getDefinitionHash()).isEqualTo("seed-hash");
        assertThat(draft.getRiskSummaryJson()).isEqualTo(DEFAULT_RISK);
        assertThat(draft.getCreatedBy()).isEqualTo("operator");

        assertThat(view).containsEntry("id", playbook.getId())
                .containsEntry("draftVersion", 1)
                .containsEntry("status", "ACTIVE")
                .containsEntry("tags", List.of("edr"));
        assertThat(view.get("latestPublishedVersion")).isNull();
    }

    @Test
    void createPlaybookDefaultsMissingTagsToEmptyList() {
        service.createPlaybook("No tags", null, null);

        ArgumentCaptor<SoarPlaybookEntity> captor = ArgumentCaptor.forClass(SoarPlaybookEntity.class);
        verify(playbooks).save(captor.capture());
        assertThat(captor.getValue().getTagsJson()).isEqualTo("[]");
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-a");
    }

    @Test
    void createPlaybookRejectsBlankName() {
        assertThatThrownBy(() -> service.createPlaybook("   ", "description", List.of("edr")))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(400);
                    assertThat(error.getReason()).contains("SOAR_INPUT_INVALID");
                });
    }

    // ----------------------------------------------------------- importDraft

    @Test
    void importDraftStoresDefinitionAsEditableDraft() throws Exception {
        SoarPlaybookEntity[] created = new SoarPlaybookEntity[1];
        PlaybookVersionEntity[] seeded = new PlaybookVersionEntity[1];
        given(playbooks.save(any(SoarPlaybookEntity.class))).willAnswer(invocation -> {
            created[0] = invocation.getArgument(0);
            return created[0];
        });
        given(versions.save(any(PlaybookVersionEntity.class))).willAnswer(invocation -> {
            seeded[0] = invocation.getArgument(0);
            return seeded[0];
        });
        given(playbooks.findByTenantIdAndId(eq("tenant-a"), anyString())).willAnswer(inv -> Optional.of(created[0]));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo(eq("tenant-a"), anyString(), eq(1)))
                .willAnswer(inv -> Optional.of(seeded[0]));
        given(validator.canonicalHash(anyString())).willReturn("seed-hash");
        given(validator.validate(anyString())).willReturn(validation(true, "imported-hash", 2, 3, 1));

        JsonNode definition = mapper.readTree(SIMPLE_DEFINITION);
        JsonNode layout = mapper.readTree("{\"x\":1}");

        Map<String, Object> result = service.importDraft("Imported", "from file", List.of("import"),
                definition, layout);

        assertThat(result).containsEntry("imported", true)
                .containsEntry("playbookId", created[0].getId())
                .containsEntry("version", 1)
                .containsEntry("status", SoarPlaybookVersionStatus.DRAFT.name())
                .containsEntry("definitionHash", "imported-hash")
                .containsEntry("playbookStatus", "ACTIVE");
        assertThat(seeded[0].getDefinitionJson()).isEqualTo(SIMPLE_DEFINITION);
        assertThat(seeded[0].getLayoutJson()).isEqualTo("{\"x\":1}");
        assertThat(seeded[0].getDefinitionHash()).isEqualTo("imported-hash");
        assertThat(seeded[0].getTenantId()).isEqualTo("tenant-a");
        JsonNode risk = mapper.readTree(seeded[0].getRiskSummaryJson());
        assertThat(risk.path("actionCount").asInt()).isEqualTo(3);
        assertThat(risk.path("highRiskActionCount").asInt()).isEqualTo(1);
        assertThat(risk.path("valid").asBoolean()).isTrue();
        assertThat(((JsonNode) result.get("definition")).path("entryNodeId").asText()).isEqualTo("start");
    }

    @Test
    void importDraftRejectsNonObjectDefinition() throws Exception {
        JsonNode arrayDefinition = mapper.readTree("[1,2]");

        assertThatThrownBy(() -> service.importDraft("Imported", null, null, arrayDefinition, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(400);
                    assertThat(error.getReason()).contains("SOAR_DEFINITION_INVALID");
                });
        assertThatThrownBy(() -> service.importDraft("Imported", null, null, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(400);
                    assertThat(error.getReason()).contains("SOAR_DEFINITION_INVALID");
                });
    }

    // -------------------------------------------------------------- listing

    @Test
    void listPlaybooksReturnsTenantScopedPage() {
        Pageable pageable = PageRequest.of(0, 10);
        given(playbooks.findByTenantId("tenant-a", pageable))
                .willReturn(new PageImpl<>(List.of(playbook("pb-1", "Contain host"))));

        Page<Map<String, Object>> page = service.listPlaybooks(pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(row -> row.get("id")).containsExactly("pb-1");
        assertThat(page.getContent().get(0)).containsEntry("name", "Contain host")
                .containsEntry("status", "ACTIVE")
                .containsEntry("owner", "operator");
        assertThat(page.getContent().get(0).get("draftVersion")).isNull();
        verify(playbooks).findByTenantId("tenant-a", pageable);
    }

    @Test
    void listPlaybooksDelegatesStatusAndOwnerFiltersToRepository() {
        Pageable pageable = PageRequest.of(0, 5);
        given(playbooks.searchByTenant("tenant-a", "ACTIVE", "alice", null, pageable))
                .willReturn(new PageImpl<>(List.of(playbook("pb-1", "Contain host"))));

        Page<Map<String, Object>> page = service.listPlaybooks(pageable, " ACTIVE ", "alice", null, "   ");

        assertThat(page.getContent()).extracting(row -> row.get("id")).containsExactly("pb-1");
        verify(playbooks).searchByTenant("tenant-a", "ACTIVE", "alice", null, pageable);
    }

    @Test
    void listPlaybooksFiltersByTagCaseInsensitively() {
        SoarPlaybookEntity edr = playbook("pb-1", "Contain host", List.of("edr", "contain"));
        SoarPlaybookEntity net = playbook("pb-2", "Block ip", List.of("net"));
        given(playbooks.findByTenantId("tenant-a")).willReturn(List.of(edr, net));

        Page<Map<String, Object>> page = service.listPlaybooks(PageRequest.of(0, 10), null, null, "EDR", null);

        assertThat(page.getContent()).extracting(row -> row.get("id")).containsExactly("pb-1");
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0)).containsEntry("tags", List.of("edr", "contain"));
    }

    @Test
    void listPlaybooksFiltersByHighRiskPublishedVersion() {
        given(playbooks.findByTenantId("tenant-a"))
                .willReturn(List.of(playbook("pb-high", "Block ip"), playbook("pb-low", "Notify")));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-high"))
                .willReturn(List.of(version("ver-high", "pb-high", 1, SoarPlaybookVersionStatus.PUBLISHED,
                        SIMPLE_DEFINITION, "{\"highRiskActionCount\":2,\"actionCount\":3}")));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-low"))
                .willReturn(List.of(version("ver-low", "pb-low", 1, SoarPlaybookVersionStatus.PUBLISHED,
                        SIMPLE_DEFINITION, "{\"highRiskActionCount\":0,\"actionCount\":2}")));

        Page<Map<String, Object>> page = service.listPlaybooks(PageRequest.of(0, 10), null, null, null, "high");

        assertThat(page.getContent()).extracting(row -> row.get("id")).containsExactly("pb-high");
    }

    @Test
    void listPlaybooksTreatsPlaybookWithoutPublishedVersionAsRiskNone() {
        given(playbooks.findByTenantId("tenant-a"))
                .willReturn(List.of(playbook("pb-draft", "Draft only"), playbook("pb-live", "Live")));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-draft"))
                .willReturn(List.of());
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-live"))
                .willReturn(List.of(version("ver-live", "pb-live", 1, SoarPlaybookVersionStatus.PUBLISHED,
                        SIMPLE_DEFINITION, "{\"highRiskActionCount\":0,\"actionCount\":2}")));

        Page<Map<String, Object>> page = service.listPlaybooks(PageRequest.of(0, 10), null, null, null, "NONE");

        assertThat(page.getContent()).extracting(row -> row.get("id")).containsExactly("pb-draft");
    }

    // ----------------------------------------------- getPlaybook / update

    @Test
    void getPlaybookIncludesOrderedVersionHistory() {
        PlaybookVersionEntity draft = version("ver-2", "pb-1", 2, SoarPlaybookVersionStatus.DRAFT);
        PlaybookVersionEntity published = version("ver-1", "pb-1", 1, SoarPlaybookVersionStatus.PUBLISHED);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "Contain host")));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-1"))
                .willReturn(List.of(draft, published));

        Map<String, Object> result = service.getPlaybook("pb-1");

        assertThat(result).containsEntry("id", "pb-1")
                .containsEntry("name", "Contain host")
                .containsEntry("draftVersion", 2);
        List<?> history = (List<?>) result.get("versions");
        assertThat(history).hasSize(2);
        Map<String, Object> newest = (Map<String, Object>) history.get(0);
        assertThat(newest).containsEntry("id", "ver-2")
                .containsEntry("status", SoarPlaybookVersionStatus.DRAFT.name())
                .containsEntry("playbookStatus", "ACTIVE")
                .containsEntry("schemaVersion", SoarDefinitionValidator.SCHEMA_VERSION);
        assertThat((Map<String, Object>) history.get(1)).containsEntry("id", "ver-1")
                .containsEntry("status", SoarPlaybookVersionStatus.PUBLISHED.name());
    }

    @Test
    void getPlaybookRejectsRowOutsideCurrentTenant() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPlaybook("pb-missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(404);
                    assertThat(error.getReason()).contains("SOAR_PLAYBOOK_NOT_FOUND");
                });
        verify(playbooks).findByTenantIdAndId("tenant-a", "pb-missing");
    }

    @Test
    void updatePlaybookAppliesMetadataAndStatusChanges() {
        SoarPlaybookEntity playbook = playbook("pb-1", "Old name");
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook));

        Map<String, Object> result = service.updatePlaybook("pb-1", " New name ", "  description  ",
                List.of("edr", "contain"), "archived", null);

        assertThat(playbook.getName()).isEqualTo("New name");
        assertThat(playbook.getDescription()).isEqualTo("description");
        assertThat(playbook.getStatus()).isEqualTo("ARCHIVED");
        assertThat(playbook.getTagsJson()).isEqualTo("[\"edr\",\"contain\"]");
        assertThat(result).containsEntry("id", "pb-1")
                .containsEntry("name", "New name")
                .containsEntry("status", "ARCHIVED");
        verify(playbooks).save(playbook);
    }

    @Test
    void updatePlaybookRejectsUnknownStatus() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));

        assertThatThrownBy(() -> service.updatePlaybook("pb-1", "name", null, null, "DELETED", null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(400);
                    assertThat(error.getReason()).contains("SOAR_INPUT_INVALID");
                });
    }

    @Test
    void updatePlaybookRejectsMoreThanThirtyTwoTags() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));
        List<String> tags = IntStream.range(0, 33).mapToObj(index -> "tag-" + index).toList();

        assertThatThrownBy(() -> service.updatePlaybook("pb-1", "name", null, tags, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(400);
                    assertThat(error.getReason()).contains("SOAR_INPUT_INVALID");
                });
    }

    @Test
    void updatePlaybookRejectsStaleRowVersion() {
        SoarPlaybookEntity playbook = playbook("pb-1", "x");
        playbook.setRowVersion(4L);
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook));

        assertThatThrownBy(() -> service.updatePlaybook("pb-1", "name", null, null, null, 9L))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).contains("SOAR_PLAYBOOK_CONFLICT");
                });
    }

    // ------------------------------------------------------- createVersion

    @Test
    void createVersionClonesLatestVersionIntoNewDraft() {
        SoarPlaybookEntity playbook = playbook("pb-1", "Contain host");
        PlaybookVersionEntity published = version("ver-1", "pb-1", 1, SoarPlaybookVersionStatus.PUBLISHED,
                SIMPLE_DEFINITION, "{\"highRiskActionCount\":1,\"actionCount\":2}");
        published.setLayoutJson("{\"layout\":true}");
        given(playbooks.findByTenantIdAndIdForUpdate("tenant-a", "pb-1")).willReturn(Optional.of(playbook));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-1"))
                .willReturn(List.of(published));
        given(versions.findFirstByTenantIdAndPlaybookIdAndStatusOrderByVersionNoDesc("tenant-a", "pb-1",
                SoarPlaybookVersionStatus.DRAFT.name())).willReturn(Optional.empty());
        given(validator.canonicalHash(SIMPLE_DEFINITION)).willReturn("clone-hash");

        Map<String, Object> result = service.createVersion("pb-1");

        ArgumentCaptor<PlaybookVersionEntity> captor = ArgumentCaptor.forClass(PlaybookVersionEntity.class);
        verify(versions).save(captor.capture());
        PlaybookVersionEntity created = captor.getValue();
        assertThat(created.getTenantId()).isEqualTo("tenant-a");
        assertThat(created.getPlaybookId()).isEqualTo("pb-1");
        assertThat(created.getVersionNo()).isEqualTo(2);
        assertThat(created.getStatus()).isEqualTo(SoarPlaybookVersionStatus.DRAFT.name());
        assertThat(created.getSchemaVersion()).isEqualTo(SoarDefinitionValidator.SCHEMA_VERSION);
        assertThat(created.getDefinitionJson()).isEqualTo(SIMPLE_DEFINITION);
        assertThat(created.getLayoutJson()).isEqualTo("{\"layout\":true}");
        assertThat(created.getRiskSummaryJson()).isEqualTo(published.getRiskSummaryJson());
        assertThat(created.getDefinitionHash()).isEqualTo("clone-hash");
        assertThat(created.getCreatedBy()).isEqualTo("operator");
        assertThat(result).containsEntry("version", 2)
                .containsEntry("status", SoarPlaybookVersionStatus.DRAFT.name())
                .containsEntry("playbookStatus", "ACTIVE");
    }

    @Test
    void createVersionRejectsExistingDraft() {
        PlaybookVersionEntity draft = version("ver-2", "pb-1", 2, SoarPlaybookVersionStatus.DRAFT);
        given(playbooks.findByTenantIdAndIdForUpdate("tenant-a", "pb-1"))
                .willReturn(Optional.of(playbook("pb-1", "x")));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-1"))
                .willReturn(List.of(draft));
        given(versions.findFirstByTenantIdAndPlaybookIdAndStatusOrderByVersionNoDesc("tenant-a", "pb-1",
                SoarPlaybookVersionStatus.DRAFT.name())).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.createVersion("pb-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).contains("SOAR_DRAFT_ALREADY_EXISTS");
                });
    }

    @Test
    void createVersionRejectsUnknownPlaybook() {
        given(playbooks.findByTenantIdAndIdForUpdate("tenant-a", "pb-missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createVersion("pb-missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(404);
                    assertThat(error.getReason()).contains("SOAR_PLAYBOOK_NOT_FOUND");
                });
    }

    // -------------------------------------------------------- version lookup

    @Test
    void getVersionByIdReturnsTenantScopedView() {
        PlaybookVersionEntity version = version("ver-9", "pb-1", 3, SoarPlaybookVersionStatus.PUBLISHED);
        given(versions.findByTenantIdAndId("tenant-a", "ver-9")).willReturn(Optional.of(version));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "x")));

        Map<String, Object> result = service.getVersionById("ver-9");

        assertThat(result).containsEntry("id", "ver-9")
                .containsEntry("playbookId", "pb-1")
                .containsEntry("version", 3)
                .containsEntry("status", SoarPlaybookVersionStatus.PUBLISHED.name())
                .containsEntry("playbookStatus", "ACTIVE");
        verify(versions).findByTenantIdAndId("tenant-a", "ver-9");
    }

    @Test
    void getVersionByIdRejectsRowOutsideCurrentTenant() {
        given(versions.findByTenantIdAndId("tenant-a", "ver-missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVersionById("ver-missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(404);
                    assertThat(error.getReason()).contains("SOAR_VERSION_NOT_FOUND");
                });
        verify(versions).findByTenantIdAndId("tenant-a", "ver-missing");
    }

    // ----------------------------------------------------------- schema

    @Test
    void definitionSchemaDescribesContract() {
        JsonNode schema = service.definitionSchema();

        assertThat(schema.path("$id").asText()).isEqualTo("https://socp.local/schema/soar.playbook");
        assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("soar.playbook");
        assertThat(schema.at("/properties/nodes/maxItems").asInt()).isEqualTo(200);
        assertThat(schema.at("/properties/limits/properties/maxNodeExecutions/maximum").asInt()).isEqualTo(500);
        assertThat(schema.at("/properties/limits/properties/maxParallelism/maximum").asInt()).isEqualTo(10);
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        assertThat(required).containsExactly("schemaVersion", "entryNodeId", "nodes", "edges");
    }

    // ------------------------------------------------------------ fixtures

    private static DefinitionValidationResult validation(boolean valid, String hash, int nodes,
                                                         int actions, int highRisk) {
        return new DefinitionValidationResult(valid,
                valid ? List.of() : List.of(DefinitionIssue.error("DEFINITION_INVALID", null, "", "bad graph")),
                List.of(DefinitionIssue.warning("DEFINITION_HINT", null, "", "hint")),
                SoarDefinitionValidator.SCHEMA_VERSION, hash, nodes, actions, highRisk);
    }

    private SoarPlaybookEntity playbook(String id, String name) {
        return playbook(id, name, List.of());
    }

    private SoarPlaybookEntity playbook(String id, String name, List<String> tags) {
        SoarPlaybookEntity entity = new SoarPlaybookEntity();
        entity.setId(id);
        entity.setTenantId("tenant-a");
        entity.setName(name);
        entity.setOwner("operator");
        entity.setStatus("ACTIVE");
        entity.setTagsJson(json(tags));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
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

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
