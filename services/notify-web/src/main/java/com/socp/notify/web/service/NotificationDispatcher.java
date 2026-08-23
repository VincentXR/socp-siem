package com.socp.notify.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.store.ChannelStore;
import com.socp.notify.web.store.NotificationDeliveryEntity;
import com.socp.notify.web.store.NotificationDeliveryRepository;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpHttpClient;
import com.socp.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Dispatches an alarm to enabled channels with per-alarm/channel idempotency. */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final int TIMEOUT = 3000;

    private final ChannelStore channels;
    private final SocpHttpClient http;
    private final NotificationDeliveryRepository deliveries;
    private final List<Map<String, Object>> dispatchLog = new CopyOnWriteArrayList<>();

    public NotificationDispatcher(ChannelStore channels, SocpHttpClient http,
                                  NotificationDeliveryRepository deliveries) {
        this.channels = channels;
        this.http = http;
        this.deliveries = deliveries;
    }

    public Map<String, Object> dispatch(Map<String, Object> alarm) {
        String alarmId = text(alarm.get("id"));
        if (alarmId == null) throw new IllegalArgumentException("alarm id is required");
        String tenant = tenant();
        List<Map<String, Object>> results = new ArrayList<>();
        int failed = 0;
        for (Channel channel : channels.enabled()) {
            Map<String, Object> result = deliveredResult(tenant, alarmId, channel);
            if (result == null) {
                result = send(channel, alarm);
                log(channel, result, alarm);
                if ("failed".equals(result.get("status"))) {
                    failed++;
                } else {
                    remember(tenant, alarmId, channel, result);
                }
            }
            results.add(result);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("alarmId", alarmId);
        response.put("ruleId", alarm.get("ruleId"));
        response.put("dispatched", results.size());
        response.put("failed", failed);
        response.put("results", results);
        return response;
    }

    private Map<String, Object> deliveredResult(String tenant, String alarmId, Channel channel) {
        return deliveries.findByIdAndTenantId(deliveryId(tenant, alarmId, channel.id()), tenant)
                .map(row -> {
                    try {
                        Map<String, Object> result = new LinkedHashMap<>(MAPPER.readValue(row.getResultJson(), MAP_TYPE));
                        result.put("duplicate", true);
                        return result;
                    } catch (Exception corruptReceipt) {
                        throw new IllegalStateException("invalid notification delivery receipt", corruptReceipt);
                    }
                })
                .orElse(null);
    }

    private void remember(String tenant, String alarmId, Channel channel, Map<String, Object> result) {
        try {
            NotificationDeliveryEntity row = new NotificationDeliveryEntity();
            row.setId(deliveryId(tenant, alarmId, channel.id()));
            row.setTenantId(tenant);
            row.setAlarmId(alarmId);
            row.setChannelId(channel.id());
            row.setResultJson(MAPPER.writeValueAsString(result));
            row.setDeliveredAt(Instant.now());
            deliveries.save(row);
        } catch (Exception persistenceFailure) {
            throw new IllegalStateException("notification receipt persistence failed", persistenceFailure);
        }
    }

    private Map<String, Object> send(Channel channel, Map<String, Object> alarm) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", channel.name());
        result.put("type", channel.type());
        if ("EMAIL".equals(channel.type())) {
            // Do not issue an idempotency receipt for a connector that did not
            // actually transmit anything. Alert delivery will keep the durable
            // hand-off retryable (and eventually mark it DEAD for operator action).
            result.put("status", "failed");
            result.put("httpStatus", 501);
            result.put("detail", "SMTP delivery is not configured: " + channel.target());
            return result;
        }
        ServiceCall call = http.postExternal(channel.target(), buildPayload(channel, alarm),
                SocpHttpClient.JSON, TIMEOUT);
        if (call == null) {
            result.put("status", "failed");
            result.put("httpStatus", 0);
            result.put("detail", "HTTP client returned no result");
            return result;
        }
        result.put("status", call.ok() ? "sent" : "failed");
        result.put("httpStatus", call.status());
        result.put("detail", call.ok()
                ? truncate(call.body(), 300)
                : truncate(call.failureReason() + " | " + call.body(), 300));
        if (!call.ok()) {
            log.warn("Notification channel failed channel={} type={} target={} alarmId={} reason={}",
                    channel.name(), channel.type(), channel.target(), alarm.get("id"), call.failureReason());
        }
        return result;
    }

    private static String buildPayload(Channel channel, Map<String, Object> alarm) {
        String type = channel.type() == null ? "" : channel.type().toUpperCase();
        Object payload = switch (type) {
            case "WEBHOOK" -> alarm;
            case "SLACK", "DINGTALK", "WECOM", "WECHAT" -> Map.of("text", imText(alarm));
            default -> Map.of("channel", channel.name(), "type", channel.type(), "alarm", alarm);
        };
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception failure) {
            throw new IllegalArgumentException("notification payload cannot be serialized", failure);
        }
    }

    private static String imText(Map<String, Object> alarm) {
        String severity = String.valueOf(alarm.getOrDefault("severity", "-"));
        String mitre = alarm.get("mitre") == null ? "" : " [" + alarm.get("mitre") + "]";
        return "[" + severity + "] " + alarm.getOrDefault("ruleName", alarm.getOrDefault("ruleId", "Alarm")) + mitre
                + "\nEntity: " + alarm.getOrDefault("entity", "-")
                + "\nDetail: " + alarm.getOrDefault("message", "-")
                + "\nTime: " + alarm.getOrDefault("occurredAt", "-")
                + "\nAlarm ID: " + alarm.getOrDefault("id", "-");
    }

    private void log(Channel channel, Map<String, Object> result, Map<String, Object> alarm) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("channel", channel.name());
        entry.put("type", channel.type());
        entry.put("alarmId", alarm.get("id"));
        entry.put("ruleId", alarm.get("ruleId"));
        entry.put("status", result.get("status"));
        entry.put("tenantId", tenant());
        if (dispatchLog.size() > 200) dispatchLog.removeFirst();
        dispatchLog.add(entry);
    }

    public List<Map<String, Object>> log() {
        String tenant = tenant();
        return dispatchLog.stream()
                .filter(entry -> tenant.equals(entry.get("tenantId")))
                .map(Map::copyOf)
                .toList();
    }

    private static String deliveryId(String tenant, String alarmId, String channelId) {
        String key = tenant + "\u0000" + alarmId + "\u0000" + channelId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
