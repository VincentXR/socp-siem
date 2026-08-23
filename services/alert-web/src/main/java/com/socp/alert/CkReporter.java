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

/** Writes alarm detail rows to ClickHouse for reporting. */
@Component
public class CkReporter {

    private static final Logger log = LoggerFactory.getLogger(CkReporter.class);
    private static final DateTimeFormatter CK_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Value("${socp.ck.url:http://localhost:8123}")
    private String ckUrl;

    @Value("${socp.ck.user:default}")
    private String user;

    @Value("${socp.ck.password:socp}")
    private String password;

    @Value("${socp.ck.enabled:true}")
    private boolean enabled;

    /** Compatibility API for non-durable callers. */
    public void reportAlarm(Alarm alarm) {
        if (!enabled || alarm == null) return;
        Thread.startVirtualThread(() -> doReport(alarm));
    }

    /** Blocking acknowledgement used by the durable delivery worker. */
    public boolean reportAlarmAndAwait(Alarm alarm) {
        if (!enabled) return true;
        return alarm != null && doReport(alarm);
    }

    private boolean doReport(Alarm alarm) {
        HttpURLConnection connection = null;
        try {
            String ts = alarm.getOccurredAt() == null
                    ? java.time.LocalDateTime.now().format(CK_TS)
                    : java.time.LocalDateTime.ofInstant(alarm.getOccurredAt(), java.time.ZoneId.systemDefault())
                    .format(CK_TS);
            String row = "{\"tenant_id\":\"%s\",\"alarm_id\":\"%s\",\"ts\":\"%s\","
                    + "\"severity\":\"%s\",\"rule_id\":\"%s\",\"rule_name\":\"%s\",\"entity\":\"%s\"}"
                    .formatted(escape(alarm.getTenantId()), escape(alarm.getId()), ts, alarm.getSeverity(),
                            escape(alarm.getRuleId()), escape(alarm.getRuleName()), escape(alarm.getEntity()));
            String sql = "INSERT INTO alert_agg.alarm_detail FORMAT JSONEachRow\n" + row + "\n";
            connection = (HttpURLConnection) new URL(ckUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            String auth = Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8));
            connection.setRequestProperty("Authorization", "Basic " + auth);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(sql.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                log.info("ClickHouse alarm detail acknowledged alarmId={} HTTP={}", alarm.getId(), code);
                return true;
            }
            log.warn("ClickHouse alarm detail rejected alarmId={} HTTP={}", alarm.getId(), code);
        } catch (Exception failure) {
            log.warn("ClickHouse alarm detail delivery failed alarmId={}: {}", alarm.getId(), failure.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return false;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
