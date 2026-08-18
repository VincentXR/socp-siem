package com.socp.alert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable source-event evidence attached to one alert. */
@Entity
@Table(name = "t_alarm_evidence")
public class AlarmEvidence extends BaseEntity {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "alarm_id", nullable = false)
    private String alarmId;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_timestamp")
    private Instant eventTimestamp;

    private String source;
    private String host;
    private String severity;

    @Column(columnDefinition = "TEXT")
    private String raw;

    @Column(name = "fields_json", columnDefinition = "TEXT")
    private String fieldsJson;

    @Column(name = "evidence_order", nullable = false)
    private int evidenceOrder;

    public AlarmEvidence() {
    }

    public static AlarmEvidence from(String alarmId, String tenantId, int order, AlarmEvidenceInput input) {
        AlarmEvidence e = new AlarmEvidence();
        e.alarmId = alarmId;
        e.setTenantId(tenantId);
        e.evidenceOrder = order;
        e.eventId = input.eventId();
        e.eventTimestamp = input.timestamp();
        e.source = input.source();
        e.host = input.host();
        e.severity = input.severity();
        e.raw = input.raw();
        e.fieldsJson = writeJson(input.fields());
        return e;
    }

    public AlarmEvidenceView view() {
        return new AlarmEvidenceView(id, eventId, eventTimestamp, source, host, severity, raw,
                readMap(fieldsJson), evidenceOrder);
    }

    private static String writeJson(Map<String, String> fields) {
        try {
            return MAPPER.writeValueAsString(fields == null ? Map.of() : fields);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new LinkedHashMap<>(MAPPER.readValue(json, new TypeReference<Map<String, String>>() {
            }));
        } catch (Exception e) {
            return Map.of();
        }
    }

    public String getId() {
        return id;
    }

    public String getAlarmId() {
        return alarmId;
    }

    public String getEventId() {
        return eventId;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public String getSource() {
        return source;
    }

    public String getHost() {
        return host;
    }

    public String getSeverity() {
        return severity;
    }

    public String getRaw() {
        return raw;
    }

    public String getFieldsJson() {
        return fieldsJson;
    }

    public int getEvidenceOrder() {
        return evidenceOrder;
    }
}
