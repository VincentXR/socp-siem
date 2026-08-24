package com.socp.detect.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.engine.AlertStreamHub;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.store.DetectionContentCatalog;
import com.socp.platform.auth.RequireRole;
import com.socp.platform.tenant.TenantContext;
import com.socp.rule.model.Alert;
import jakarta.validation.Valid;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detection rule management, local ingest, alert stream, and runtime APIs. */
@RestController
@RequestMapping("/api/v1")
public class RuleController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DetectEngineService engine;
    private final AlertStreamHub streamHub;
    private final Validator validator;

    public RuleController(DetectEngineService engine, AlertStreamHub streamHub, Validator validator) {
        this.engine = engine;
        this.streamHub = streamHub;
        this.validator = validator;
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> listRules() {
        return engine.listRules();
    }

    /** Versioned detection content metadata used by review and release tooling. */
    @GetMapping("/rules/content-manifest")
    public Map<String, Object> contentManifest() {
        return engine.contentManifest();
    }

    /** Validate a rule without persisting or hot-reloading it. */
    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules/validate")
    public Map<String, Object> validateRule(@RequestBody Map<String, Object> spec) {
        Map<String, Object> enriched = DetectionContentCatalog.enrich(spec);
        List<String> errors = DetectionContentCatalog.validateSpec(enriched);
        return Map.of("valid", errors.isEmpty(), "errors", errors, "spec", enriched);
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules")
    public Map<String, Object> addRule(@RequestBody Map<String, Object> spec) {
        return engine.addRule(spec);
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/rules/{id}")
    public Map<String, Object> updateRule(@PathVariable String id, @RequestBody Map<String, Object> spec) {
        spec.put("id", id);
        return engine.updateRule(spec);
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable String id) {
        return Map.of("removed", engine.deleteRule(id));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules/reload")
    public Map<String, Object> reload() {
        Map<String, Object> response = new LinkedHashMap<>();
        engine.reload();
        response.put("reloaded", true);
        response.put("rules", engine.listRules().size());
        return response;
    }

    /** Local HTTP ingress for verification; production events normally arrive through Kafka. */
    @PostMapping("/ingest")
    public ResponseEntity<DetectionIngestResponse> ingest(@Valid @RequestBody DetectionIngestRequest request) {
        boolean accepted = engine.ingest(request.toSecurityEvent(TenantContext.get()));
        Object queueLoad = engine.stats().get("queueLoad");
        if (!accepted) {
            return ResponseEntity.status(503).header("Retry-After", "2")
                    .body(new DetectionIngestResponse(false, queueLoad, "queue_full"));
        }
        return ResponseEntity.ok(new DetectionIngestResponse(true, queueLoad, null));
    }

    /** NDJSON batch ingress used by SEARCH forwarding. */
    @PostMapping(value = "/ingest/bulk", consumes = {
            MediaType.APPLICATION_JSON_VALUE, "application/x-ndjson", MediaType.TEXT_PLAIN_VALUE
    })
    public DetectionBulkIngestResponse ingestBulk(@RequestBody String body) {
        if (body != null && body.length() > 16 * 1024 * 1024) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "bulk body exceeds 16 MiB");
        }
        int accepted = 0;
        int rejected = 0;
        if (body != null) {
            String[] lines = body.split("\\n", -1);
            if (lines.length > 1000) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "bulk body exceeds 1000 events");
            }
            for (String line : lines) {
                String payload = line.trim();
                if (payload.isEmpty()) continue;
                if (payload.length() > 256 * 1024) {
                    rejected++;
                    continue;
                }
                try {
                    DetectionIngestRequest request = MAPPER.readValue(payload, DetectionIngestRequest.class);
                    if (!validator.validate(request).isEmpty()) {
                        rejected++;
                    } else if (engine.ingest(request.toSecurityEvent(TenantContext.get()))) accepted++;
                    else rejected++;
                } catch (Exception malformed) {
                    rejected++;
                }
            }
        }
        return new DetectionBulkIngestResponse(accepted, rejected, engine.stats().get("queueLoad"));
    }

    @GetMapping("/alerts")
    public List<Alert> alerts() {
        return engine.recentAlerts();
    }

    /** Servlet SSE endpoint with a small heartbeat to keep intermediary proxies alive. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        PrintWriter output = response.getWriter();
        output.write(": socp connected\n\n");
        output.flush();
        streamHub.add(TenantContext.require(), output);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(3_000);
                output.write(": ping\n\n");
                output.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception disconnected) {
            // Client disconnected or response output was closed.
        } finally {
            streamHub.remove(output);
        }
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return engine.stats();
    }
}
