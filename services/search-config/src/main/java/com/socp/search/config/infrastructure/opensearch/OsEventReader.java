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
import java.security.cert.X509Certificate;
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

    public OsEventReader() {
        this(new OpenSearchProperties());
    }

    @Autowired
    public OsEventReader(OpenSearchProperties properties) {
        this.properties = properties;
    }

    /**
     * 搜索：OS 可用返回同构 QueryResult，不可用/失败返回 null（调用方回退本地检索库）。
     */
    public SplEngine.QueryResult search(String q, int size) {
        if (!properties.isEnabled()) return null;
        String query = q == null ? "" : q.trim();
        try {
            String endpoint = properties.getUrl() + "/" + properties.getSearchIndex() + "/_search";
            HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            String auth = Base64.getEncoder().encodeToString(
                    (properties.getUsername() + ":" + properties.getPassword()).getBytes(StandardCharsets.UTF_8));
            c.setRequestProperty("Authorization", "Basic " + auth);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/json");
            if (c instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(trustAllSsl());
                https.setHostnameVerifier((hostname, session) -> true);
            }
            byte[] request = tenantQuery(query, size);
            try (OutputStream output = c.getOutputStream()) {
                output.write(request);
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                log.warn("OpenSearch 检索失败 HTTP {}（回退 H2 检索）", code);
                return null;
            }
            byte[] body = c.getInputStream().readAllBytes();
            c.disconnect();
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
        String tenant = TenantContext.get();
        if (tenant == null || tenant.isBlank()) tenant = "default";
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

    private static SSLSocketFactory trustAllSsl() throws Exception {
        TrustManager[] tm = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm, new SecureRandom());
        return ctx.getSocketFactory();
    }
}
