package com.socp.detect.web.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.api.request.DetectionIngestRequest;
import com.socp.detect.web.api.response.DetectionBulkIngestResponse;
import com.socp.detect.web.api.response.DetectionIngestResponse;
import com.socp.detect.web.config.DetectRuntimeRole;
import com.socp.detect.web.engine.AlertStreamHub;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.Alert;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/** Worker-owned local ingest, alert stream and runtime statistics surface. */
@RestController
@DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
@RequestMapping("/api/v1")
public class DetectionRuntimeController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DetectEngineService engine;
    private final AlertStreamHub streamHub;
    private final Validator validator;

    public DetectionRuntimeController(DetectEngineService engine, AlertStreamHub streamHub,
                                      Validator validator) {
        this.engine = engine;
        this.streamHub = streamHub;
        this.validator = validator;
    }

    /** Local HTTP ingress for verification; production events normally arrive through Kafka. */
    public ResponseEntity<DetectionIngestResponse> ingest(@Valid @RequestBody DetectionIngestRequest request) {
        return ingest(request, null);
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/ingest")
    public ResponseEntity<DetectionIngestResponse> ingest(@Valid @RequestBody DetectionIngestRequest request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false)
                                                          String idempotencyKey) {
        String fallback = normalizedIdempotencyKey(idempotencyKey);
        boolean accepted = engine.ingest(request.toSecurityEvent(TenantContext.require(), fallback));
        Object queueLoad = engine.stats().get("queueLoad");
        if (!accepted) {
            return ResponseEntity.status(503).header("Retry-After", "2")
                    .body(new DetectionIngestResponse(false, queueLoad, "queue_full"));
        }
        return ResponseEntity.ok(new DetectionIngestResponse(true, queueLoad, null));
    }

    /** NDJSON batch ingress used by SEARCH forwarding. */
    public DetectionBulkIngestResponse ingestBulk(@RequestBody String body) {
        return ingestBulk(body, null);
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping(value = "/ingest/bulk", consumes = {
            MediaType.APPLICATION_JSON_VALUE, "application/x-ndjson", MediaType.TEXT_PLAIN_VALUE
    })
    public DetectionBulkIngestResponse ingestBulk(@RequestBody String body,
                                                  @RequestHeader(value = "Idempotency-Key", required = false)
                                                  String idempotencyKey) {
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
            String requestKey = normalizedIdempotencyKey(idempotencyKey);
            int lineNumber = 0;
            for (String line : lines) {
                int currentLine = lineNumber++;
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
                    } else if (engine.ingest(request.toSecurityEvent(TenantContext.require(),
                            requestKey == null ? null : requestKey + ":" + currentLine))) accepted++;
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

    private static String normalizedIdempotencyKey(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isBlank()) return null;
        if (normalized.length() > 256) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must not exceed 256 characters");
        }
        return normalized;
    }
}
