package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.artifact.SoarArtifactStore;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import com.socp.soar.web.domain.v2.SoarRunStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.security.MessageDigest;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

/**
 * Coverage for the connector-free paths of {@link SoarV2Service}: filtered
 * playbook listing (tag/risk predicates), retry/rerun cloning with variable
 * snapshots, bounded approval evidence for malformed or oversized snapshots,
 * artifact storage degradation, manual-task paging and dead-letter guards.
 *
 * <p>Connector registry and connection repository are deliberately {@code null};
 * every collaborator is a Mockito mock and nothing touches the network, a
 * database or Temporal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SoarV2ServiceRetentionExportCoverageTest {

    private static final String SAFE_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
            + "{\"id\":\"notify\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send-channel\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";

    private static final String HIGH_RISK_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
            + "{\"id\":\"contain\",\"type\":\"ACTION\",\"actionRef\":\"endpoint/isolate-host\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";

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
    private SoarArtifactRepository artifacts;
    @Mock
    private SoarArtifactStore artifactStore;

    private final ObjectMapper mapper = new ObjectMapper();

    private SoarV2Service service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        SoarTestIdentity.setOperator();
        service = new SoarV2Service(playbooks, versions, runs, dispatches, nodes, events, approvals,
                validator, mapper, temporal, attempts, manualTasks, signals, null, null);
        service.setArtifacts(artifacts);
        // appendEvent locks the owning run before it allocates a sequence number.
        given(runs.findByTenantIdAndIdForUpdate(eq("tenant-a"), anyString()))
                .willReturn(Optional.of(run("run-lock", "req-lock")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SoarTestIdentity.clear();
    }

    // ------------------------------------------------------------------ listPlaybooks

    @Test
    void listPlaybooksAppliesTagAndRiskFiltersOverTenantSet() {
        SoarPlaybookEntity mediumRisk = playbook("pb-a", "[\"containment\",\"network\"]");
        SoarPlaybookEntity noRisk = playbook("pb-b", "[\"triage\"]");
        SoarPlaybookEntity brokenTags = playbook("pb-c", "{oops");
        given(playbooks.findByTenantId("tenant-a")).willReturn(List.of(mediumRisk, noRisk, brokenTags));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-a"))
                .willReturn(List.of(publishedVersion("{\"highRiskActionCount\":0,\"actionCount\":2}")));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-b"))
                .willReturn(List.of(publishedVersion("{\"highRiskActionCount\":0,\"actionCount\":0}")));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-c"))
                .willReturn(List.of());
        Pageable pageable = PageRequest.of(0, 10);

        Page<Map<String, Object>> medium = service.listPlaybooks(pageable, null, null, "CONTAINMENT", "MEDIUM");
        assertThat(medium.getTotalElements()).isEqualTo(1);
        assertThat(medium.getContent().get(0)).containsEntry("id", "pb-a");

        Page<Map<String, Object>> low = service.listPlaybooks(pageable, null, null, "triage", "LOW");
        assertThat(low.getContent()).extracting(view -> view.get("id")).containsExactly("pb-b");

        Page<Map<String, Object>> unknownRisk = service.listPlaybooks(pageable, null, null, "triage", "WHATEVER");
        assertThat(unknownRisk.getContent()).isEmpty();
    }

    // ------------------------------------------------------------------------- rerun

    @Test
    void rerunCreatesNewSeriesAndRestoresRedactedVariables() {
        // stubSourceVersion installs the repository stub; mutate THAT instance,
        // otherwise the output snapshot never reaches the rerun flow.
        stubSourceVersion(SAFE_DEFINITION, "PUBLISHED");
        SoarRunEntity original =
                runs.findByTenantIdAndId("tenant-a", "run-1").orElseThrow();
        original.setOutputJson("{\"variables\":{\"caseId\":\"C-1\",\"secretKey\":\"abc\"}}");
        given(validator.validate(SAFE_DEFINITION)).willReturn(validation(true, 0));

        Map<String, Object> result = service.rerun("run-1", "because", true);

        assertThat(result).containsEntry("duplicate", false).containsEntry("triggerType", "RERUN");
        ArgumentCaptor<SoarRunEntity> cloneCaptor = ArgumentCaptor.forClass(SoarRunEntity.class);
        verify(runs).save(cloneCaptor.capture());
        SoarRunEntity clone = cloneCaptor.getValue();
        assertThat(clone.getExecutionSeriesId()).isNotEqualTo(original.getExecutionSeriesId());
        assertThat(clone.getInputJson()).contains("C-1").doesNotContain("abc").contains("[REDACTED]");
        assertThat(clone.getInputJson()).contains("resumeFromNodeId");
        ArgumentCaptor<SoarDispatchOutboxEntity> outboxCaptor =
                ArgumentCaptor.forClass(SoarDispatchOutboxEntity.class);
        verify(dispatches).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void rerunRejectsUnpublishedOrInvalidSourceVersion() {
        stubSourceVersion(SAFE_DEFINITION, "DRAFT");
        assertRejected(HttpStatus.CONFLICT, "SOAR_VERSION_NOT_PUBLISHED",
                () -> service.rerun("run-1", "because", true));

        stubSourceVersion(SAFE_DEFINITION, "PUBLISHED");
        given(validator.validate(SAFE_DEFINITION)).willReturn(validation(false, 0));
        assertRejected(HttpStatus.CONFLICT, "SOAR_DEFINITION_INVALID",
                () -> service.rerun("run-1", "because", true));
    }

    @Test
    void rerunWithHighRiskSourceWaitsForApproval() {
        stubSourceVersion(HIGH_RISK_DEFINITION, "PUBLISHED");
        given(validator.validate(HIGH_RISK_DEFINITION)).willReturn(validation(true, 1));

        Map<String, Object> result = service.rerun("run-1", "contain again", true);

        assertThat(result).containsEntry("status", SoarRunStatus.WAITING_APPROVAL.name());
        ArgumentCaptor<SoarApprovalEntity> approvalCaptor = ArgumentCaptor.forClass(SoarApprovalEntity.class);
        verify(approvals).save(approvalCaptor.capture());
        assertThat(approvalCaptor.getValue().getActionRef()).isEqualTo("endpoint/isolate-host");
        assertThat(approvalCaptor.getValue().getReason())
                .isEqualTo("retry/rerun contains high-risk response actions");
        ArgumentCaptor<SoarDispatchOutboxEntity> outboxCaptor =
                ArgumentCaptor.forClass(SoarDispatchOutboxEntity.class);
        verify(dispatches).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo("HOLD");
    }

    @Test
    void rerunWithCorruptSnapshotJsonStaysBounded() {
        SoarRunEntity original = run("run-1", "req-1");
        original.setInputJson("{bad json");
        original.setOutputJson("{bad json");
        stubSourceVersion(SAFE_DEFINITION, "PUBLISHED");
        given(validator.validate(SAFE_DEFINITION)).willReturn(validation(true, 0));

        Map<String, Object> result = service.rerun("run-1", "because", true);

        assertThat(result).containsEntry("duplicate", false);
        ArgumentCaptor<SoarRunEntity> cloneCaptor = ArgumentCaptor.forClass(SoarRunEntity.class);
        verify(runs).save(cloneCaptor.capture());
        assertThat(cloneCaptor.getValue().getInputJson()).contains("_soar");
    }

    // ------------------------------------------------- bounded approval evidence paths

    @Test
    void malformedPublishedDefinitionIsRejectedBeforeApprovalEvidence() {
        given(runs.findByTenantIdAndRequestId(eq("tenant-a"), anyString())).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-9"))
                .willReturn(Optional.of(version("ver-9", "pb-1", "PUBLISHED", "{ bad json")));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1"))
                .willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(validator.validate("{ bad json")).willReturn(validation(true, 1));

        assertRejected(HttpStatus.CONFLICT, "SOAR_SUB_PLAYBOOK_DEFINITION_INVALID",
                () -> service.queueManualRun("req-malformed", "ver-9", null, null));
        verify(approvals, org.mockito.Mockito.never()).save(any(SoarApprovalEntity.class));
    }

    @Test
    void oversizedApprovalSnapshotIsTruncated() {
        String hugeTarget = "{\"schemaVersion\":\"soar.playbook/v2\","
                + "\"nodes\":[{\"id\":\"a\",\"type\":\"ACTION\",\"actionRef\":\"endpoint/isolate-host\","
                + "\"target\":{\"blob\":\"" + "x".repeat(70 * 1024) + "\"}}],"
                + "\"approvalPolicy\":{\"allowedRoles\":[\"R1\"]}}";
        given(runs.findByTenantIdAndRequestId(eq("tenant-a"), anyString())).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-9"))
                .willReturn(Optional.of(version("ver-9", "pb-1", "PUBLISHED", hugeTarget)));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1"))
                .willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(validator.validate(hugeTarget)).willReturn(validation(true, 1));

        service.queueManualRun("req-huge", "ver-9", null, null);

        ArgumentCaptor<SoarApprovalEntity> approvalCaptor = ArgumentCaptor.forClass(SoarApprovalEntity.class);
        verify(approvals).save(approvalCaptor.capture());
        assertThat(approvalCaptor.getValue().getTargetSnapshotJson()).contains("truncated")
                .contains("originalBytes");
        assertThat(approvalCaptor.getValue().getActionRef()).isEqualTo("endpoint/isolate-host");
    }

    // ---------------------------------------------------------------------- artifacts

    @Test
    void artifactOperationsWithoutStorageAdapterDegradeGracefully() {
        SoarV2Service bare = new SoarV2Service(playbooks, versions, runs, dispatches, nodes, events,
                approvals, validator, mapper, temporal, attempts, manualTasks, signals, null, null);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run("run-1", "req-1")));

        Page<Map<String, Object>> empty = bare.listArtifacts("run-1", PageRequest.of(0, 10));
        assertThat(empty).isEmpty();

        assertRejected(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_STORAGE_UNAVAILABLE",
                () -> bare.uploadArtifact("run-1", null, "application/json", null, mapper.createObjectNode()));

        assertRejected(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_STORAGE_UNAVAILABLE",
                () -> bare.getArtifact("art-1"));
    }

    @Test
    void expiredArtifactIsReportedGone() {
        SoarArtifactEntity expired = artifact("art-old");
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        given(artifacts.findByTenantIdAndId("tenant-a", "art-old")).willReturn(Optional.of(expired));

        assertRejected(HttpStatus.GONE, "SOAR_ARTIFACT_EXPIRED", () -> service.getArtifact("art-old"));
    }

    @Test
    void uploadArtifactValidatesNodeBindingAndSize() {
        SoarRunEntity owner = run("run-1", "req-1");
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(owner));

        // nodeRunId does not exist in this tenant
        given(nodes.findByTenantIdAndId("tenant-a", "node-x")).willReturn(Optional.empty());
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_NODE_RUN_NOT_FOUND",
                () -> service.uploadArtifact("run-1", "node-x", "application/json", null,
                        mapper.createObjectNode()));

        // nodeRunId belongs to a different run
        SoarNodeRunEntity foreign = node("node-y", "run-other");
        given(nodes.findByTenantIdAndId("tenant-a", "node-y")).willReturn(Optional.of(foreign));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_NODE_RUN_MISMATCH",
                () -> service.uploadArtifact("run-1", "node-y", "application/json", null,
                        mapper.createObjectNode()));

        // oversized inline content
        ObjectNode huge = mapper.createObjectNode();
        huge.put("blob", "x".repeat(70 * 1024));
        assertRejected(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_STORAGE_UNAVAILABLE",
                () -> service.uploadArtifact("run-1", null, "application/json", null, huge));

        // happy path: long nodeRunId is truncated to the bounded column length
        // before the node-run lookup and persistence.
        SoarNodeRunEntity bound = node("z".repeat(64), "run-1");
        given(nodes.findByTenantIdAndId("tenant-a", "z".repeat(64))).willReturn(Optional.of(bound));
        given(artifacts.save(any(SoarArtifactEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> uploaded = service.uploadArtifact("run-1", "z".repeat(100),
                "application/json", "internal", mapper.createObjectNode());

        assertThat(uploaded).containsEntry("classification", "INTERNAL");
        assertThat(((String) uploaded.get("nodeRunId"))).hasSize(64);
        verify(events).save(any(SoarRunEventEntity.class));
    }

    @Test
    void largeArtifactUsesExternalStoreAndKeepsOnlyMetadataInPostgres() {
        SoarRunEntity owner = run("run-1", "req-1");
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(owner));
        given(artifactStore.put(eq("tenant-a"), eq("run-1"), anyString(), eq("application/json"), any()))
                .willAnswer(invocation -> {
                    byte[] bytes = invocation.getArgument(4);
                    return new SoarArtifactStore.StoredArtifact("s3://soar-artifacts/object-1",
                            bytes.length, sha256Hex(bytes));
                });
        service.setArtifactStore(artifactStore);
        given(artifacts.save(any(SoarArtifactEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        ObjectNode huge = mapper.createObjectNode();
        huge.put("blob", "x".repeat(70 * 1024));

        Map<String, Object> uploaded = service.uploadArtifact("run-1", null, "application/json",
                "internal", huge);

        assertThat(uploaded).containsEntry("storageRef", "s3://soar-artifacts/object-1")
                .containsEntry("sizeBytes", 70L * 1024 + 11L);
        ArgumentCaptor<SoarArtifactEntity> captor = ArgumentCaptor.forClass(SoarArtifactEntity.class);
        verify(artifacts).save(captor.capture());
        assertThat(captor.getValue().getInlineJson()).isNull();
        verify(artifactStore).put(eq("tenant-a"), eq("run-1"), anyString(), eq("application/json"), any());
    }

    @Test
    void externalArtifactContentIsIntegrityCheckedBeforeItIsReturned() {
        SoarArtifactEntity external = artifact("art-external");
        external.setInlineJson(null);
        external.setStorageRef("s3://soar-artifacts/object-1");
        external.setSizeBytes(3L);
        external.setSha256("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        given(artifacts.findByTenantIdAndId("tenant-a", "art-external")).willReturn(Optional.of(external));
        given(artifactStore.read("s3://soar-artifacts/object-1"))
                .willReturn(Optional.of("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service.setArtifactStore(artifactStore);

        assertThat(service.getArtifactContent("art-external")).isEqualTo("abc");

        given(artifactStore.read("s3://soar-artifacts/object-1"))
                .willReturn(Optional.of("tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertRejected(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_INTEGRITY_FAILED",
                () -> service.getArtifactContent("art-external"));
    }

    @Test
    void metadataFailureAttemptsToRemoveUploadedExternalObject() {
        SoarRunEntity owner = run("run-1", "req-1");
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(owner));
        given(artifactStore.put(eq("tenant-a"), eq("run-1"), anyString(), eq("application/json"), any()))
                .willAnswer(invocation -> {
                    byte[] bytes = invocation.getArgument(4);
                    return new SoarArtifactStore.StoredArtifact("s3://soar-artifacts/orphan", bytes.length,
                            sha256Hex(bytes));
                });
        service.setArtifactStore(artifactStore);
        doThrow(new IllegalStateException("database unavailable")).when(artifacts).save(any(SoarArtifactEntity.class));
        ObjectNode huge = mapper.createObjectNode();
        huge.put("blob", "x".repeat(70 * 1024));

        assertThat(catchThrowable(() -> service.uploadArtifact("run-1", null, "application/json", null, huge)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verify(artifactStore).delete("s3://soar-artifacts/orphan");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    // -------------------------------------------------------------- manual task paging

    @Test
    void listManualTasksSupportsPaging() {
        given(manualTasks.findByTenantIdAndStatusOrderByDueAtAsc("tenant-a", "PENDING"))
                .willReturn(List.of(manualTask("t-1"), manualTask("t-2")));
        Page<Map<String, Object>> firstPage = service.listManualTasks(true, PageRequest.of(0, 1));
        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(1);
        assertThat(service.listManualTasks(true, PageRequest.of(9, 10)).getContent()).isEmpty();

        given(manualTasks.findByTenantIdOrderByCreatedAtDesc(eq("tenant-a"), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(manualTask("t-3"))));
        assertThat(service.listManualTasks(false, PageRequest.of(0, 10)).getContent())
                .extracting(view -> view.get("id")).containsExactly("t-3");
    }

    // ------------------------------------------------------------------- dead letters

    @Test
    void deadLetterOperationsRejectLiveAndMissingRows() {
        // requeue: signal row that is not DEAD
        given(dispatches.findByTenantIdAndId("tenant-a", "s-1")).willReturn(Optional.empty());
        given(signals.findByTenantIdAndId("tenant-a", "s-1"))
                .willReturn(Optional.of(signal("s-1", "run-2", "PENDING")));
        assertRejected(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD",
                () -> service.requeueDead("s-1", "why"));

        // discard: dispatch row that is not DEAD
        given(dispatches.findByTenantIdAndId("tenant-a", "d-live"))
                .willReturn(Optional.of(outbox("d-live", "run-1", "PENDING")));
        assertRejected(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD",
                () -> service.discardDead("d-live", "why"));

        // discard: signal row that is not DEAD
        given(dispatches.findByTenantIdAndId("tenant-a", "s-2")).willReturn(Optional.empty());
        given(signals.findByTenantIdAndId("tenant-a", "s-2"))
                .willReturn(Optional.of(signal("s-2", "run-2", "PENDING")));
        assertRejected(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD",
                () -> service.discardDead("s-2", "why"));

        // discard: unknown id
        given(dispatches.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());
        given(signals.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());
        assertRejected(HttpStatus.NOT_FOUND, "SOAR_OUTBOX_NOT_FOUND",
                () -> service.discardDead("missing", "why"));
    }

    // ------------------------------------------------------------- broken JSON views

    @Test
    void brokenJsonProjectionsDegradeGracefully() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1"))
                .willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 2))
                .willReturn(Optional.of(version("ver-2", "pb-1", "PUBLISHED", "{oops")));

        Map<String, Object> view = service.getVersion("pb-1", 2);
        assertThat(view.get("definition")).isInstanceOf(ObjectNode.class);
        assertThat((ObjectNode) view.get("definition")).isEmpty();

        SoarApprovalEntity approval = approval("apr-1", "run-1", "PENDING");
        approval.setTargetSnapshotJson("{oops");
        approval.setPolicyJson("{oops");
        given(approvals.findByTenantIdOrderByCreatedAtDesc("tenant-a")).willReturn(List.of(approval));

        List<Map<String, Object>> approvalsView = service.listApprovals();
        assertThat(approvalsView).hasSize(1);
        assertThat(approvalsView.get(0).get("targetSnapshot")).isInstanceOf(ObjectNode.class);
    }

    // ------------------------------------------------------------------------ helpers

    private void stubSourceVersion(String definition, String status) {
        SoarRunEntity original = run("run-1", "req-1");
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(original));
        given(runs.findByTenantIdAndRequestId(eq("tenant-a"), anyString())).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-1"))
                .willReturn(Optional.of(version("ver-1", "pb-1", status, definition)));
    }

    private static void assertRejected(HttpStatus status, String code, ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(status);
        assertThat(thrown.getMessage()).contains(code);
    }

    private static DefinitionValidationResult validation(boolean valid, int highRiskActions) {
        return new DefinitionValidationResult(valid, List.of(), List.of(),
                SoarDefinitionValidator.SCHEMA_VERSION, "hash-1", 3, 1, highRiskActions);
    }

    private static SoarRunEntity run(String id, String requestId) {
        SoarRunEntity run = new SoarRunEntity();
        run.setId(id);
        run.setTenantId("tenant-a");
        run.setRequestId(requestId);
        run.setExecutionSeriesId(id);
        run.setPlaybookId("pb-1");
        run.setPlaybookVersionId("ver-1");
        run.setPlaybookVersionNo(1);
        run.setDefinitionHash("hash-1");
        run.setTriggerType("MANUAL");
        run.setStatus(SoarRunStatus.FAILED.name());
        run.setRequestedBy("alice");
        run.setInputJson("{\"subject\":{\"type\":\"alert\"},\"inputs\":{}}");
        run.setCreatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        return run;
    }

    private static PlaybookVersionEntity version(String id, String playbookId, String status, String definition) {
        PlaybookVersionEntity version = new PlaybookVersionEntity();
        version.setId(id);
        version.setTenantId("tenant-a");
        version.setPlaybookId(playbookId);
        version.setVersionNo(1);
        version.setStatus(status);
        version.setSchemaVersion(SoarDefinitionValidator.SCHEMA_VERSION);
        version.setDefinitionJson(definition);
        version.setLayoutJson("{}");
        version.setDefinitionHash("hash-1");
        version.setRiskSummaryJson("{\"highRiskActionCount\":0,\"actionCount\":1}");
        version.setCreatedBy("alice");
        version.setCreatedAt(Instant.now());
        version.setUpdatedAt(Instant.now());
        return version;
    }

    private static PlaybookVersionEntity publishedVersion(String riskSummaryJson) {
        PlaybookVersionEntity version = version("ver-p", "pb-x", "PUBLISHED", SAFE_DEFINITION);
        version.setRiskSummaryJson(riskSummaryJson);
        return version;
    }

    private static SoarPlaybookEntity playbook(String id, String tagsJson) {
        SoarPlaybookEntity playbook = new SoarPlaybookEntity();
        playbook.setId(id);
        playbook.setTenantId("tenant-a");
        playbook.setName("Playbook " + id);
        playbook.setOwner("alice");
        playbook.setStatus("ACTIVE");
        playbook.setTagsJson(tagsJson);
        playbook.setCreatedAt(Instant.now());
        playbook.setUpdatedAt(Instant.now());
        return playbook;
    }

    private static SoarApprovalEntity approval(String id, String runId, String status) {
        SoarApprovalEntity approval = new SoarApprovalEntity();
        approval.setId(id);
        approval.setTenantId("tenant-a");
        approval.setRunId(runId);
        approval.setApprovalKey("gate-" + id);
        approval.setStatus(status);
        approval.setRequestedBy("alice");
        approval.setRequiredApprovals(1);
        approval.setCreatedAt(Instant.now());
        approval.setExpiresAt(Instant.now().plusSeconds(3600));
        return approval;
    }

    private static SoarArtifactEntity artifact(String id) {
        SoarArtifactEntity artifact = new SoarArtifactEntity();
        artifact.setId(id);
        artifact.setTenantId("tenant-a");
        artifact.setRunId("run-1");
        artifact.setMediaType("application/json");
        artifact.setSizeBytes(16L);
        artifact.setSha256("sha-1");
        artifact.setStorageRef("db://soar-artifacts/" + id);
        artifact.setClassification("INTERNAL");
        artifact.setInlineJson("{}");
        artifact.setCreatedAt(Instant.now());
        artifact.setExpiresAt(Instant.now().plusSeconds(3600));
        return artifact;
    }

    private static SoarNodeRunEntity node(String id, String runId) {
        SoarNodeRunEntity node = new SoarNodeRunEntity();
        node.setId(id);
        node.setTenantId("tenant-a");
        node.setRunId(runId);
        node.setNodeId("contain");
        node.setIterationPath("/");
        node.setNodeType("ACTION");
        node.setStatus("FAILED");
        node.setInputJson("{}");
        node.setOutputJson("{}");
        node.setUpdatedAt(Instant.now());
        return node;
    }

    private static SoarManualTaskEntity manualTask(String id) {
        SoarManualTaskEntity task = new SoarManualTaskEntity();
        task.setId(id);
        task.setTenantId("tenant-a");
        task.setRunId("run-1");
        task.setNodeId("node-1");
        task.setFormSchemaJson("{}");
        task.setStatus("PENDING");
        task.setCreatedAt(Instant.now());
        task.setDueAt(Instant.now().plusSeconds(3600));
        return task;
    }

    private static SoarDispatchOutboxEntity outbox(String id, String runId, String status) {
        SoarDispatchOutboxEntity outbox = new SoarDispatchOutboxEntity();
        outbox.setId(id);
        outbox.setTenantId("tenant-a");
        outbox.setRunId(runId);
        outbox.setStatus(status);
        outbox.setAttempts(3);
        outbox.setLastError("boom");
        outbox.setNextAttemptAt(Instant.now());
        outbox.setCreatedAt(Instant.now());
        outbox.setUpdatedAt(Instant.now());
        return outbox;
    }

    private static SoarSignalOutboxEntity signal(String id, String runId, String status) {
        SoarSignalOutboxEntity signal = new SoarSignalOutboxEntity();
        signal.setId(id);
        signal.setTenantId("tenant-a");
        signal.setRunId(runId);
        signal.setSignalType("APPROVAL");
        signal.setSignalKey("gate-1");
        signal.setPayloadJson("{}");
        signal.setStatus(status);
        signal.setAttempts(2);
        signal.setNextAttemptAt(Instant.now());
        signal.setCreatedAt(Instant.now());
        signal.setUpdatedAt(Instant.now());
        return signal;
    }
}
