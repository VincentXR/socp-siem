package com.socp.alert.service;

import com.socp.alert.domain.Alarm;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.alert.config.ClickHouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes alarm detail rows to ClickHouse for reporting. */
@Component
public class CkReporter {

    private static final Logger log = LoggerFactory.getLogger(CkReporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter CK_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final ClickHouseProperties properties;

    public CkReporter() {
        this(new ClickHouseProperties());
    }

    @Autowired
    public CkReporter(ClickHouseProperties properties) {
        this.properties = properties;
    }

    /** Compatibility API for non-durable callers. */
    public void reportAlarm(Alarm alarm) {
        if (!properties.isEnabled() || alarm == null) return;
        Thread.startVirtualThread(() -> doReport(alarm));
    }

    /** Blocking acknowledgement used by the durable delivery worker. */
    public boolean reportAlarmAndAwait(Alarm alarm) {
        if (!properties.isEnabled()) return true;
        return alarm != null && doReport(alarm);
    }

    private boolean doReport(Alarm alarm) {
        HttpURLConnection connection = null;
        try {
            String ts = alarm.getOccurredAt() == null
                    ? java.time.LocalDateTime.now().format(CK_TS)
                    : java.time.LocalDateTime.ofInstant(alarm.getOccurredAt(), java.time.ZoneId.systemDefault())
                    .format(CK_TS);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("tenant_id", alarm.getTenantId());
            values.put("alarm_id", alarm.getId());
            values.put("ts", ts);
            values.put("severity", alarm.getSeverity() == null ? "" : alarm.getSeverity().name());
            values.put("rule_id", alarm.getRuleId());
            values.put("rule_name", alarm.getRuleName());
            values.put("title", alarm.getTitle());
            values.put("entity", alarm.getEntity());
            values.put("row_version", 1L);
            String row = MAPPER.writeValueAsString(values);
            String sql = "INSERT INTO alert_agg.alarm_detail FORMAT JSONEachRow\n" + row + "\n";
            // ClickHouse is intentionally at-least-once at the transport boundary.
            // The stable token lets replicated installations suppress an identical
            // retry; ReplacingMergeTree + logical uniqExact queries cover legacy
            // MergeTree installations where server-side insert dedup is unavailable.
            String token = URLEncoder.encode(alarm.getTenantId() + "\u0000" + alarm.getId(),
                    StandardCharsets.UTF_8);
            String separator = properties.getUrl().contains("?") ? "&" : "?";
            String endpoint = properties.getUrl() + separator + "insert_deduplication_token=" + token;
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            String auth = Base64.getEncoder().encodeToString(
                    (properties.getUser() + ":" + properties.getPassword()).getBytes(StandardCharsets.UTF_8));
            connection.setRequestProperty("Authorization", "Basic " + auth);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(sql.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                log.info("ClickHouse alarm detail acknowledged alarmId={} HTTP={}", alarm.getId(), code);
                return true;
            }
            String response = connection.getErrorStream() == null ? ""
                    : new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (response.length() > 512) response = response.substring(0, 512);
            log.warn("ClickHouse alarm detail rejected alarmId={} HTTP={} body={}", alarm.getId(), code, response);
        } catch (Exception failure) {
            log.warn("ClickHouse alarm detail delivery failed alarmId={}: {}", alarm.getId(), failure.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return false;
    }

}
