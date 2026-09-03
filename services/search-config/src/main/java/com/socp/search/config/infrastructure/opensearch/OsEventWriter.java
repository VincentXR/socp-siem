package com.socp.search.config.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.SearchEvent;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.search.config.config.OpenSearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Writes normalized search events to daily OpenSearch indices. Kafka callers
 * use the synchronous per-item result so an offset advances only after a
 * durable index acknowledgement or a broker-acknowledged diagnostic DLQ write.
 */
@Component
public class OsEventWriter {

    private static final Logger log = LoggerFactory.getLogger(OsEventWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final DateTimeFormatter INDEX_DATE = DateTimeFormatter
            .ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);

    private final OpenSearchProperties properties;
    private final OpenSearchHttpTransport transport;

    public OsEventWriter() {
        this(new OpenSearchProperties());
    }

    public OsEventWriter(OpenSearchProperties properties) {
        this(properties, new OpenSearchHttpTransport(properties));
    }

    @Autowired
    public OsEventWriter(OpenSearchProperties properties, OpenSearchHttpTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    private volatile boolean templateInitialized = false;

    @jakarta.annotation.PostConstruct
    public void init() {
        if (!properties.isEnabled()) return;
        Thread.startVirtualThread(this::ensureIndexTemplate);
    }

    /**
     * Initializes the explicit Index Template in OpenSearch for socp-events-*
     * so that key security fields are properly mapped to keyword/date/text.
     */
    public synchronized boolean ensureIndexTemplate() {
        if (!properties.isEnabled() || templateInitialized) return true;
        try {
            var response = transport.exchange("PUT", OpenSearchIndexTemplate.PATH,
                    "application/json", OpenSearchIndexTemplate.payloadBytes());
            int code = response.status();
            if (code >= 200 && code < 300) {
                log.info("OpenSearch index template initialized successfully: socp-events-template (HTTP {})", code);
                templateInitialized = true;
                return true;
            } else {
                log.debug("OpenSearch index template check/init returned HTTP {}", code);
                return false;
            }
        } catch (Exception ex) {
            log.debug("OpenSearch index template initialization deferred: {}", ex.getMessage());
            return false;
        }
    }

    /** Asynchronous compatibility path used only when no Kafka indexing path is configured. */
    public void writeEvents(List<SearchEvent> es) {
        if (!properties.isEnabled() || es == null || es.isEmpty()) return;
        Thread.startVirtualThread(() -> doWrite(es));
    }

    /**
     * Performs one complete bulk request and classifies every item after the
     * OpenSearch response has been received.
     */
    public BulkWriteResult writeEventsAndAwait(List<SearchEvent> es) {
        if (es == null || es.isEmpty()) return BulkWriteResult.empty();
        if (!properties.isEnabled()) {
            return BulkWriteResult.retryAll(es, "writer_disabled",
                    "OpenSearch writer is disabled", null, 0L);
        }
        return doWrite(es);
    }

    private BulkWriteResult doWrite(List<SearchEvent> es) {
        long started = System.nanoTime();
        try {
            if (!ensureIndexTemplate()) {
                log.warn("OpenSearch index template is unavailable; bulk write was not attempted");
                return BulkWriteResult.retryAll(es, "template_not_ready",
                        "OpenSearch index template is not ready", null, elapsedMs(started));
            }
            List<SearchEvent> indexEvents = es.stream()
                    .map(OsEventWriter::prepareForIndex)
                    .toList();
            StringBuilder sb = new StringBuilder(es.size() * 256);
            for (SearchEvent e : indexEvents) {
                String index = "socp-events-" + INDEX_DATE.format(e.timestamp());
                // Tenant-scoped stable ID turns redelivery into an idempotent
                // overwrite without allowing equal source IDs to collide.
                String tenant = e.fields() == null ? null : e.fields().get("tenant_id");
                if (tenant == null || !com.socp.platform.tenant.context.TenantContext.isValid(tenant)) {
                    throw new IllegalArgumentException("OpenSearch event tenant_id is required");
                }
                String documentId = tenant + "|" + e.eventId();
                sb.append("{\"index\":{\"_index\":")
                        .append(MAPPER.writeValueAsString(index))
                        .append(",\"_id\":")
                        .append(MAPPER.writeValueAsString(documentId))
                        .append("}}\n");
                sb.append(MAPPER.writeValueAsString(e)).append('\n');
            }
            var response = transport.exchange("POST", "/_bulk", "application/x-ndjson",
                    sb.toString().getBytes(StandardCharsets.UTF_8));
            int code = response.status();
            if (code >= 200 && code < 300) {
                String resp = response.bodyText();
                BulkWriteResult result = parseBulkResult(resp, es, elapsedMs(started));
                int failed = result.retryableFailures().size() + result.permanentFailures().size();
                if (failed > 0) {
                    String reason = result.retryableFailures().isEmpty()
                            ? result.permanentFailures().getFirst().reason()
                            : result.retryableFailures().getFirst().reason();
                    log.warn("OpenSearch bulk 写入 {}/{} 条失败（items/response validation，HTTP {}）-> {} : {}",
                            failed, es.size(), code, properties.getUrl(), reason);
                    return result;
                }
                log.info("OpenSearch bulk durable acknowledgement events={} HTTP={}", es.size(), code);
                return result;
            } else {
                log.warn("OpenSearch bulk failed HTTP {}; Kafka offset remains uncommitted", code);
                if (isPermanentStatus(code)) {
                    return BulkWriteResult.permanentAll(es, "bulk_http_" + code,
                            "OpenSearch rejected the bulk request with HTTP " + code,
                            code, elapsedMs(started));
                }
                return BulkWriteResult.retryAll(es, "bulk_http_" + code,
                        "OpenSearch bulk request failed with HTTP " + code, code, elapsedMs(started));
            }
        } catch (Exception e) {
            log.warn("OpenSearch write failed; Kafka offset remains uncommitted: {}", e.toString());
            return BulkWriteResult.retryAll(es, "bulk_transport_failure",
                    e.getClass().getSimpleName(), null, elapsedMs(started));
        }
    }

    /**
     * Keep a malformed typed value from rejecting the complete raw event.
     *
     * <p>The canonical event sent through Kafka is deliberately left untouched:
     * Detection may still need the original string to evaluate a rule. The
     * OpenSearch projection is the typed boundary, so invalid IP literals are
     * moved to a searchable {@code *_raw} field and annotated instead of
     * poisoning the whole bulk item. This is also needed for daily indices
     * created before the template's {@code ignore_malformed} guard was added.</p>
     */
    private static SearchEvent prepareForIndex(SearchEvent event) {
        if (event == null || event.fields() == null || event.fields().isEmpty()) return event;
        java.util.Map<String, String> fields = null;
        String warnings = event.fields().get("parse_warning");
        for (String key : List.of("src_ip", "dst_ip", "http_status", "count", "bytes")) {
            String value = event.fields().get(key);
            if (value == null) continue;
            String trimmed = value.trim();
            String warning = typedFieldWarning(key, trimmed);
            if (warning == null) {
                if (!trimmed.equals(value)) {
                    if (fields == null) fields = new LinkedHashMap<>(event.fields());
                    fields.put(key, trimmed);
                }
                continue;
            }
            if (fields == null) fields = new LinkedHashMap<>(event.fields());
            fields.remove(key);
            fields.putIfAbsent(key + "_raw", value);
            warnings = appendWarning(warnings, warning + ":" + key);
        }
        if (fields == null) return event;
        if (warnings != null && !warnings.isBlank()) fields.put("parse_warning", warnings);
        log.debug("OpenSearch projection moved malformed typed fields eventId={} warning={}",
                event.eventId(), warnings);
        return new SearchEvent(event.eventId(), event.timestamp(), event.source(), event.host(),
                event.severity(), event.msg(), java.util.Map.copyOf(fields),
                event.ecs() == null ? java.util.Map.of() : event.ecs());
    }

    private static String appendWarning(String existing, String warning) {
        if (existing == null || existing.isBlank()) return warning;
        if (List.of(existing.split(",")).contains(warning)) return existing;
        return existing + "," + warning;
    }

    private static boolean isIpLiteral(String value) {
        if (value == null || value.isBlank()) return false;
        if (value.indexOf(':') >= 0) {
            if (value.indexOf('%') >= 0) return false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (!(c == ':' || c == '.' || c >= '0' && c <= '9'
                        || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) return false;
            }
            try {
                return InetAddress.getByName(value) instanceof Inet6Address;
            } catch (Exception invalid) {
                return false;
            }
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || (octet.length() > 1 && octet.charAt(0) == '0')
                    || octet.length() > 3) return false;
            int number = 0;
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') return false;
                number = number * 10 + c - '0';
            }
            if (number > 255) return false;
        }
        return true;
    }

    private static String typedFieldWarning(String key, String value) {
        return switch (key) {
            case "src_ip", "dst_ip" -> isIpLiteral(value) ? null : "invalid_ip";
            case "http_status" -> isInteger(value) ? null : "invalid_integer";
            case "count", "bytes" -> isLong(value) ? null : "invalid_long";
            default -> null;
        };
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean isLong(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /**
     * Validate the complete bulk response, not only its top-level HTTP status.
     * OpenSearch can return HTTP 200 with a partial/empty items array; treating
     * that response as success would advance Kafka and permanently skip events.
     */
    private static BulkWriteResult parseBulkResult(String resp, List<SearchEvent> events, long elapsedMs) {
        try {
            var root = MAPPER.readValue(resp, com.fasterxml.jackson.databind.JsonNode.class);
            var items = root.get("items");
            if (items == null || !items.isArray() || items.size() != events.size()) {
                return BulkWriteResult.retryAll(events, "bulk_response_invalid",
                        "OpenSearch bulk response item count did not match the request", 200, elapsedMs);
            }
            List<String> acknowledged = new java.util.ArrayList<>();
            List<BulkWriteResult.Failure> retryable = new java.util.ArrayList<>();
            List<BulkWriteResult.Failure> permanent = new java.util.ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                var it = items.get(i);
                var idx = it.get("index");
                int status = idx == null ? 0 : idx.path("status").asInt(0);
                if (idx != null && !idx.has("error") && status >= 200 && status < 300) {
                    acknowledged.add(events.get(i).eventId());
                    continue;
                }
                var error = idx == null ? null : idx.get("error");
                String type = error == null ? "bulk_item_invalid"
                        : error.path("type").asText("bulk_item_failure");
                String reason = error == null ? "OpenSearch bulk item acknowledgement is missing"
                        : error.path("reason").asText(type);
                BulkWriteResult.Failure failure = new BulkWriteResult.Failure(
                        i, events.get(i).eventId(), type, reason, status == 0 ? null : status);
                if (isPermanentStatus(status)) {
                    permanent.add(failure);
                } else {
                    retryable.add(failure);
                }
            }
            if (root.path("errors").asBoolean(false) && retryable.isEmpty() && permanent.isEmpty()) {
                return BulkWriteResult.retryAll(events, "bulk_response_invalid",
                        "OpenSearch reported bulk errors without failed items", 200, elapsedMs);
            }
            long took = root.has("took") ? root.path("took").asLong(elapsedMs) : elapsedMs;
            return new BulkWriteResult(acknowledged, retryable, permanent, took);
        } catch (Exception e) {
            return BulkWriteResult.retryAll(events, "bulk_response_invalid",
                    "OpenSearch bulk response could not be parsed", 200, elapsedMs);
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static boolean isPermanentStatus(int status) {
        return status == 400 || status == 422;
    }

}
