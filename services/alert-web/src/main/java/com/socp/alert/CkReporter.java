package com.socp.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * ClickHouse 写入器（报表层接线）：告警创建时把明细行异步写入
 * `alert_agg.alarm_detail`，REPORT 报表聚合查询直接走 CK。失败静默降级
 * （不影响告警主链路——PG 仍是事实源）。
 */
@Component
public class CkReporter {

    private static final Logger log = LoggerFactory.getLogger(CkReporter.class);

    @Value("${socp.ck.url:http://localhost:8123}")
    private String ckUrl;

    @Value("${socp.ck.user:default}")
    private String user;

    @Value("${socp.ck.password:socp}")
    private String password;

    @Value("${socp.ck.enabled:true}")
    private boolean enabled;

    /** 异步 best-effort 写入一行告警明细 */
    public void reportAlarm(Alarm a) {
        if (!enabled || a == null) return;
        Thread.startVirtualThread(() -> doReport(a));
    }

    private void doReport(Alarm a) {
        try {
            String row = "{\"tenant_id\":\"%s\",\"ts\":\"%s\",\"severity\":\"%s\",\"rule_id\":\"%s\",\"rule_name\":\"%s\",\"entity\":\"%s\"}"
                    .formatted(esc(a.getTenantId()), a.getOccurredAt() == null
                                    ? DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now())
                                    : a.getOccurredAt(),
                            a.getSeverity(), esc(a.getRuleId()), esc(a.getRuleName()), esc(a.getEntity()));
            String sql = "INSERT INTO alert_agg.alarm_detail FORMAT JSONEachRow\n" + row + "\n";
            HttpURLConnection c = (HttpURLConnection) new URL(ckUrl).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            String auth = Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8));
            c.setRequestProperty("Authorization", "Basic " + auth);
            try (OutputStream os = c.getOutputStream()) {
                os.write(sql.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                log.info("ClickHouse 写入告警明细 {} (HTTP {})", a.getId(), code);
            } else {
                log.warn("ClickHouse 写入失败 HTTP {} (静默降级)", code);
            }
            c.disconnect();
        } catch (Exception e) {
            log.debug("ClickHouse 写入异常（静默降级）: {}", e.toString());
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
