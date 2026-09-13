package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.artifact.SoarArtifactStore;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded artifact commands extracted from {@link SoarService}.  The facade
 * keeps the HTTP transaction/audit contract while this collaborator owns
 * payload validation and object-store integrity checks.
 */
final class SoarArtifactCommandService {

    private static final Logger log = LoggerFactory.getLogger(SoarArtifactCommandService.class);
    private static final long MAX_ARTIFACT_BYTES = 10L * 1024 * 1024;
    private static final long INLINE_ARTIFACT_BYTES = 64L * 1024;

    private final SoarService service;
    private final SoarNodeRunRepository nodes;
    private final ObjectMapper mapper;
    private SoarArtifactRepository artifacts;
    private SoarArtifactStore artifactStore;

    SoarArtifactCommandService(SoarService service) {
        this.service = service;
        this.nodes = service.nodes;
        this.mapper = service.mapper;
        this.artifacts = service.artifacts;
        this.artifactStore = service.artifactStore;
    }

    void setArtifacts(SoarArtifactRepository artifacts) { this.artifacts = artifacts; }
    void setArtifactStore(SoarArtifactStore artifactStore) { this.artifactStore = artifactStore; }

    private String tenant() { return service.tenant(); }
    private static String actor() { return SoarService.actor(); }
    private static String limit(String value, int max) { return SoarService.limit(value, max); }
    private static ResponseStatusException error(HttpStatus status, String code, String message) {
        return SoarService.error(status, code, message);
    }
    private Object redact(Object value) { return service.redact(value); }
    private String write(Object value) { return service.write(value); }
    private SoarRunEntity run(String id) { return service.run(id); }
    private SoarArtifactEntity artifact(String id) { return service.artifact(id); }
    private Map<String, Object> artifactView(SoarArtifactEntity value) { return service.artifactView(value); }
    private void appendEvent(String runId, String type, String actor, String summary, Map<String, Object> detail) {
        service.appendEvent(runId, type, actor, summary, detail);
    }
    private static String sha256(String value) { return SoarService.sha256(value); }
    private static String sha256(byte[] value) { return SoarService.sha256(value); }

    public String getArtifactContent(String id) {
        SoarArtifactEntity artifact = artifact(id);
        if (artifact.getInlineJson() != null) return artifact.getInlineJson();
        if (artifactStore != null) {
            try {
                return artifactStore.read(artifact.getStorageRef())
                        .map(bytes -> decodeExternalArtifact(artifact, bytes))
                        .orElseThrow(() -> error(HttpStatus.GONE, "SOAR_ARTIFACT_CONTENT_GONE",
                                "artifact content is no longer available"));
            } catch (ResponseStatusException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_CONTENT_UNAVAILABLE",
                        "artifact storage could not serve the content");
            }
        }
        throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_CONTENT_UNAVAILABLE",
                "artifact storage adapter cannot serve this artifact");
    }

    public Map<String, Object> uploadArtifact(String runId, String nodeRunId,
                                               String mediaType, String classification,
                                               JsonNode content) {
        SoarRunEntity owner = run(runId); // tenant authorization before accepting any content
        if (artifacts == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_STORAGE_UNAVAILABLE",
                    "artifact storage adapter is not configured");
        }
        String type = mediaType == null || mediaType.isBlank() ? "application/json" : mediaType.trim();
        if (type.length() > 255 || !type.matches("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+(?:;.*)?")) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_ARTIFACT_INVALID", "invalid mediaType");
        }
        String kind = classification == null || classification.isBlank()
                ? "INTERNAL" : classification.trim().toUpperCase();
        if (!Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED").contains(kind)) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_ARTIFACT_INVALID", "invalid classification");
        }
        // Artifacts are evidence, but they must not become a second secret
        // store.  Apply the same key-based redaction used for run inputs
        // before persisting inline content or exposing it through the content
        // endpoint.
        String boundNodeRunId = nodeRunId == null || nodeRunId.isBlank() ? null : limit(nodeRunId, 64);
        if (boundNodeRunId != null) {
            SoarNodeRunEntity node = nodes.findByTenantIdAndId(tenant(), boundNodeRunId)
                    .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "SOAR_NODE_RUN_NOT_FOUND",
                            "nodeRunId does not belong to this tenant"));
            if (!runId.equals(node.getRunId()) || !owner.getId().equals(node.getRunId())) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_NODE_RUN_MISMATCH",
                        "nodeRunId does not belong to the requested run");
            }
        }
        Object jsonValue = content == null ? mapper.createObjectNode() : mapper.convertValue(content, Object.class);
        Object sanitized = redact(jsonValue);
        String inline = write(sanitized);
        long size = inline.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_ARTIFACT_BYTES) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "SOAR_ARTIFACT_TOO_LARGE",
                    "artifact exceeds the 10 MiB hard limit");
        }
        if (size > INLINE_ARTIFACT_BYTES && artifactStore == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_STORAGE_UNAVAILABLE",
                    "large artifact requires the configured object-store adapter");
        }
        SoarArtifactEntity artifact = new SoarArtifactEntity();
        SoarArtifactStore.StoredArtifact external = null;
        artifact.setId(UUID.randomUUID().toString().replace("-", ""));
        artifact.setTenantId(tenant());
        artifact.setRunId(runId);
        artifact.setNodeRunId(boundNodeRunId);
        artifact.setMediaType(type);
        byte[] payload = inline.getBytes(StandardCharsets.UTF_8);
        if (artifactStore != null && size > INLINE_ARTIFACT_BYTES) {
            try {
                external = artifactStore.put(tenant(), runId, artifact.getId(),
                        type, payload);
                if (external == null || external.storageRef() == null || external.storageRef().isBlank()
                        || external.sizeBytes() != payload.length
                        || external.sha256() == null
                        || !MessageDigest.isEqual(external.sha256().getBytes(StandardCharsets.US_ASCII),
                        sha256(payload).getBytes(StandardCharsets.US_ASCII))) {
                    throw new IllegalStateException("SOAR_ARTIFACT_STORE_INVALID_RESULT");
                }
                artifact.setSizeBytes(external.sizeBytes());
                artifact.setSha256(external.sha256());
                artifact.setStorageRef(external.storageRef());
            } catch (RuntimeException failure) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_STORAGE_UNAVAILABLE",
                        "artifact storage could not persist the payload");
            }
        } else {
            artifact.setSizeBytes(size);
            artifact.setSha256(sha256(inline));
            artifact.setStorageRef("db://soar-artifacts/" + artifact.getId());
        }
        artifact.setClassification(kind);
        artifact.setInlineJson(artifactStore != null && size > INLINE_ARTIFACT_BYTES ? null : inline);
        artifact.setCreatedAt(Instant.now());
        artifact.setExpiresAt(Instant.now().plusSeconds(30L * 24 * 3600));
        try {
            SoarArtifactEntity saved = artifacts.save(artifact);
            if (saved == null) throw new IllegalStateException("artifact metadata save returned no row");
            appendEvent(runId, "ARTIFACT_UPLOADED", actor(), "SOAR artifact uploaded",
                    Map.of("artifactId", saved.getId(), "sizeBytes", size));
            return artifactView(saved);
        } catch (RuntimeException failure) {
            deleteOrphanedArtifact(external);
            throw failure;
        }
    }

    private String decodeExternalArtifact(SoarArtifactEntity artifact, byte[] bytes) {
        String expectedSha = artifact.getSha256();
        if (bytes == null || artifact.getSizeBytes() < 0 || artifact.getSizeBytes() > MAX_ARTIFACT_BYTES
                || bytes.length > MAX_ARTIFACT_BYTES || expectedSha == null || expectedSha.length() != 64
                || !expectedSha.matches("[0-9a-fA-F]{64}") || bytes.length != artifact.getSizeBytes()
                || !MessageDigest.isEqual(sha256(bytes).getBytes(StandardCharsets.US_ASCII),
                expectedSha.getBytes(StandardCharsets.US_ASCII))) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_INTEGRITY_FAILED",
                    "artifact content failed integrity verification");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void deleteOrphanedArtifact(SoarArtifactStore.StoredArtifact external) {
        if (external == null || artifactStore == null || external.storageRef() == null) return;
        try {
            artifactStore.delete(external.storageRef());
        } catch (RuntimeException cleanupFailure) {
            // There is no metadata row to retry against after a transaction
            // rollback. Keep the error bounded and leave operational cleanup
            // to the provider's lifecycle/retention policy.
            log.warn("SOAR artifact orphan cleanup deferred after metadata failure");
        }
    }
}
