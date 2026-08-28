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

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * OpenSearch 写入器（生产检索库接线）：把归一化检索事件以 bulk API 写入
 * `socp-events-yyyy.MM.dd` 按天索引。HTTPS 自签证书忽略校验；失败静默降级
 * （不影响采集管线——事件在本地 H2 检索库仍完整保留）。
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
    public boolean ensureIndexTemplate() {
        if (!properties.isEnabled() || templateInitialized) return true;
        HttpURLConnection c = null;
        try {
            String templatePayload = """
                    {
                      "index_patterns": ["socp-events-*"],
                      "template": {
                        "settings": {
                          "number_of_shards": 1,
                          "number_of_replicas": 0
                        },
                        "mappings": {
                          "properties": {
                            "eventId": { "type": "keyword" },
                            "timestamp": { "type": "date" },
                            "@timestamp": { "type": "date" },
                            "source": { "type": "keyword" },
                            "host": { "type": "keyword" },
                            "severity": { "type": "keyword" },
                            "category": { "type": "keyword" },
                            "msg": { "type": "text" },
                            "fields": { "type": "object", "dynamic": true },
                            "tags": { "type": "object", "dynamic": true }
                          }
                        }
                      }
                    }
                    """;
            var response = transport.exchange("PUT", "/_index_template/socp-events-template",
                    "application/json", templatePayload.getBytes(StandardCharsets.UTF_8));
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
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Asynchronous compatibility path used only when no Kafka indexing path is configured. */
    public void writeEvents(List<SearchEvent> es) {
        if (!properties.isEnabled() || es == null || es.isEmpty()) return;
        Thread.startVirtualThread(() -> doWrite(es));
    }

    /**
     * Performs a complete bulk request and returns only after every item has
     * been durably acknowledged by OpenSearch.
     */
    public boolean writeEventsAndAwait(List<SearchEvent> es) {
        if (!properties.isEnabled() || es == null || es.isEmpty()) return true;
        return doWrite(es);
    }

    private boolean doWrite(List<SearchEvent> es) {
        HttpURLConnection c = null;
        try {
            StringBuilder sb = new StringBuilder(es.size() * 256);
            for (SearchEvent e : es) {
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
                int failed = countBulkErrors(resp);
                if (failed != 0) {
                    log.warn("OpenSearch bulk 写入 {}/{} 条失败（items error，HTTP {}）-> {} : {}",
                    failed, es.size(), code, properties.getUrl(), firstError(resp));
                    return false;
                }
                log.info("OpenSearch bulk durable acknowledgement events={} HTTP={}", es.size(), code);
                return true;
            } else {
                log.warn("OpenSearch bulk failed HTTP {}; Kafka offset remains uncommitted", code);
                return false;
            }
        } catch (Exception e) {
            log.warn("OpenSearch write failed; Kafka offset remains uncommitted: {}", e.toString());
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 统计 bulk 响应里 items[].index.error 出现的次数。 */
    private static int countBulkErrors(String resp) {
        try {
            var root = MAPPER.readValue(resp, com.fasterxml.jackson.databind.JsonNode.class);
            var items = root.get("items");
            if (root.path("errors").asBoolean(false) && (items == null || !items.isArray())) return -1;
            if (items == null || !items.isArray()) return -1;
            int n = 0;
            for (var it : items) {
                var idx = it.get("index");
                if (idx != null && idx.has("error")) n++;
            }
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 提取第一条错误信息（如 mapping 冲突原因），便于直接定位。 */
    private static String firstError(String resp) {
        try {
            var root = MAPPER.readValue(resp, com.fasterxml.jackson.databind.JsonNode.class);
            var items = root.get("items");
            if (items == null || !items.isArray()) return "";
            for (var it : items) {
                var idx = it.get("index");
                if (idx != null && idx.has("error")) {
                    var reason = idx.get("error").get("reason");
                    return reason == null ? String.valueOf(idx.get("error")) : reason.asText();
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

}
