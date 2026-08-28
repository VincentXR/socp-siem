package com.socp.search.config.infrastructure.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.service.SplEngine;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.socp.platform.tenant.context.TenantContext;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenSearch 检索读取端（修复"只写不读"缺口）：原生 HttpURLConnection 读
 * socp-events-* 索引的 _search 端点（query_string 语义），返回与 SplEngine.QueryResult 同构的结果；
 * 不可达/超时/异常返回 {@code null}，由 SearchController 回退 H2 + SplEngine。
 *
 * <p>复用 OsEventWriter 的连接约定：HTTPS 自签忽略校验 + Basic 认证 + 3s/5s 超时。
 */
@Component
public class OsEventReader {

    private static final Logger log = LoggerFactory.getLogger(OsEventReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenSearchProperties properties;
    private final OpenSearchHttpTransport transport;

    public OsEventReader() {
        this(new OpenSearchProperties());
    }

    public OsEventReader(OpenSearchProperties properties) {
        this(properties, new OpenSearchHttpTransport(properties));
    }

    @Autowired
    public OsEventReader(OpenSearchProperties properties, OpenSearchHttpTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    /**
     * 搜索：OS 可用返回同构 QueryResult，不可用/失败返回 null（调用方回退本地检索库）。
     */
    public SplEngine.QueryResult search(String q, int size) {
        if (!properties.isEnabled()) return null;
        String query = q == null ? "" : q.trim();
        try {
            byte[] request = tenantQuery(query, size);
            var response = transport.exchange("POST", "/" + properties.getSearchIndex() + "/_search",
                    "application/json", request);
            int code = response.status();
            if (code < 200 || code >= 300) {
                log.warn("OpenSearch 检索失败 HTTP {}（回退 H2 检索）", code);
                return null;
            }
            byte[] body = response.body();
            SplEngine.QueryResult r = parse(body, size);
            log.info("OpenSearch 检索命中 {} 条 (q={})", r.total(), query);
            return r;
        } catch (Exception e) {
            log.warn("OpenSearch 检索异常（回退 H2 检索）: {}", e.toString());
            return null;
        }
    }

    private static byte[] tenantQuery(String query, int size) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("size", Math.min(Math.max(size, 1), 500));
        ArrayNode sort = root.putArray("sort");
        sort.addObject().putObject("timestamp").put("order", "desc");

        ObjectNode bool = root.putObject("query").putObject("bool");
        String tenant = TenantContext.require();
        bool.putArray("filter").addObject().putObject("term")
                .put("fields.tenant_id.keyword", tenant);
        if (query == null || query.isBlank()) {
            bool.putArray("must").addObject().putObject("match_all");
        } else {
            bool.putArray("must").addObject().putObject("query_string").put("query", query);
        }
        return MAPPER.writeValueAsBytes(root);
    }

    /** 解析 _search 响应：hits.hits[]._source → SearchEvent（字段与写入端对称）。 */
    private SplEngine.QueryResult parse(byte[] body, int size) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode hits = root.path("hits").path("hits");
        List<SearchEvent> events = new ArrayList<>();
        int total = root.path("hits").path("total").path("value").asInt(hits.size());
        for (JsonNode h : hits) {
            JsonNode src = h.path("_source");
            if (src.isMissingNode()) continue;
            SearchEvent e = toEvent(src);
            if (e != null) events.add(e);
        }
        return new SplEngine.QueryResult(total, events,
                new SplEngine.QueryResult.Stat("opensearch", List.of()));
    }

    @SuppressWarnings("unchecked")
    private SearchEvent toEvent(JsonNode src) {
        try {
            String eventId = src.path("eventId").asText("");
            if (eventId.isBlank()) eventId = UUID.randomUUID().toString();
            String ts = src.path("timestamp").asText("");
            Instant timestamp = ts.isBlank() ? Instant.now() : Instant.parse(ts);
            String source = src.path("source").asText("unknown");
            String host = src.path("host").asText("unknown");
            String severity = src.path("severity").asText("INFO");
            String msg = src.path("msg").asText("");
            Map<String, String> fields = strMap(src.path("fields"));
            Map<String, String> ecs = strMap(src.path("ecs"));
            return new SearchEvent(eventId, timestamp, source, host, severity, msg, fields, ecs);
        } catch (Exception e) {
            log.warn("OpenSearch 文档解析失败: {}", e.toString());
            return null;
        }
    }

    private static Map<String, String> strMap(JsonNode node) {
        Map<String, String> m = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(en -> m.put(en.getKey(), en.getValue().asText()));
        }
        return m;
    }

}
