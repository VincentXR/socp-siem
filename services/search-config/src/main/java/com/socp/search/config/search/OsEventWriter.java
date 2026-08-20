package com.socp.search.config.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.security.cert.X509Certificate;
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

    @Value("${socp.opensearch.url:https://localhost:9200}")
    private String url;

    @Value("${socp.opensearch.username:admin}")
    private String username;

    @Value("${socp.opensearch.password:Socp!Sec2026xK}")
    private String password;

    @Value("${socp.opensearch.enabled:true}")
    private boolean enabled;

    private volatile SSLSocketFactory sslSocketFactory;

    /** Asynchronous compatibility path used only when no Kafka indexing path is configured. */
    public void writeEvents(List<SearchEvent> es) {
        if (!enabled || es == null || es.isEmpty()) return;
        Thread.startVirtualThread(() -> doWrite(es));
    }

    /**
     * Performs a complete bulk request and returns only after every item has
     * been durably acknowledged by OpenSearch.
     */
    public boolean writeEventsAndAwait(List<SearchEvent> es) {
        if (!enabled || es == null || es.isEmpty()) return true;
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
                String documentId = e.fields().getOrDefault("tenant_id", "default")
                        + "|" + e.eventId();
                sb.append("{\"index\":{\"_index\":")
                        .append(MAPPER.writeValueAsString(index))
                        .append(",\"_id\":")
                        .append(MAPPER.writeValueAsString(documentId))
                        .append("}}\n");
                sb.append(MAPPER.writeValueAsString(e)).append('\n');
            }
            c = (HttpURLConnection) new URL(url + "/_bulk").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Content-Type", "application/x-ndjson");
            String auth = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            c.setRequestProperty("Authorization", "Basic " + auth);
            if (c instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(sslSocketFactory());
                https.setHostnameVerifier((hostname, session) -> true);
            }
            try (OutputStream os = c.getOutputStream()) {
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                String resp = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int failed = countBulkErrors(resp);
                if (failed != 0) {
                    log.warn("OpenSearch bulk 写入 {}/{} 条失败（items error，HTTP {}）-> {} : {}",
                            failed, es.size(), code, url, firstError(resp));
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

    private SSLSocketFactory sslSocketFactory() throws Exception {
        SSLSocketFactory current = sslSocketFactory;
        if (current != null) return current;
        synchronized (this) {
            if (sslSocketFactory == null) sslSocketFactory = createTrustAllSsl();
            return sslSocketFactory;
        }
    }

    private static SSLSocketFactory createTrustAllSsl() throws Exception {
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
