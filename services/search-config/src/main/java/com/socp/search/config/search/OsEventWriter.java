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
import java.time.LocalDate;
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

    @Value("${socp.opensearch.url:https://localhost:9200}")
    private String url;

    @Value("${socp.opensearch.username:admin}")
    private String username;

    @Value("${socp.opensearch.password:Socp!Sec2026xK}")
    private String password;

    @Value("${socp.opensearch.enabled:true}")
    private boolean enabled;

    /** 异步 best-effort 写入（不阻塞采集热路径） */
    public void writeEvents(List<SearchEvent> es) {
        if (!enabled || es == null || es.isEmpty()) return;
        Thread.startVirtualThread(() -> doWrite(es));
    }

    private void doWrite(List<SearchEvent> es) {
        String index = "socp-events-" + DateTimeFormatter.ofPattern("yyyy.MM.dd").format(LocalDate.now());
        try {
            StringBuilder sb = new StringBuilder(es.size() * 256);
            for (SearchEvent e : es) {
                sb.append("{\"index\":{\"_index\":\"").append(index).append("\"}}\n");
                sb.append(MAPPER.writeValueAsString(e)).append('\n');
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url + "/_bulk").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Content-Type", "application/x-ndjson");
            String auth = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            c.setRequestProperty("Authorization", "Basic " + auth);
            if (c instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(trustAllSsl());
                https.setHostnameVerifier((hostname, session) -> true);
            }
            try (OutputStream os = c.getOutputStream()) {
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                log.info("OpenSearch bulk 写入 {} 事件 -> {} (HTTP {})", es.size(), index, code);
            } else {
                log.warn("OpenSearch bulk 失败 HTTP {} (静默降级)", code);
            }
            c.disconnect();
        } catch (Exception e) {
            log.warn("OpenSearch 写入异常（静默降级）: {}", e.toString());
        }
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
