package com.socp.notify.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.persistence.store.ChannelStore;
import com.socp.notify.web.persistence.entity.NotificationDeliveryEntity;
import com.socp.notify.web.persistence.repository.NotificationDeliveryRepository;
import com.socp.notify.web.persistence.entity.NotificationDispatchLogEntity;
import com.socp.notify.web.persistence.repository.NotificationDispatchLogRepository;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.tenant.context.TenantContext;
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
    private final NotificationDispatchLogRepository dispatchLogs;
    private final SmtpNotificationSender smtpSender;
    public NotificationDispatcher(ChannelStore channels, SocpHttpClient http,
                                  NotificationDeliveryRepository deliveries,
                                  NotificationDispatchLogRepository dispatchLogs,
                                  SmtpNotificationSender smtpSender) {
        this.channels = channels;
        this.http = http;
        this.deliveries = deliveries;
        this.dispatchLogs = dispatchLogs;
        this.smtpSender = smtpSender;
    }

    public Map<String, Object> dispatch(Map<String, Object> alarm) {
        String alarmId = text(alarm.get("id"));
        if (alarmId == null) throw new IllegalArgumentException("alarm id is required");
        String tenant = tenant();
        List<Channel> enabledChannels = channels.enabled();

        // 并发扇出各渠道通知，避免慢 Webhook 阻塞整体通知链路
        List<java.util.concurrent.CompletableFuture<Map<String, Object>>> futures = enabledChannels.stream()
                .map(channel -> java.util.concurrent.CompletableFuture.supplyAsync(
                        TenantContext.wrap(tenant, () -> {
                    Map<String, Object> result = deliveredResult(tenant, alarmId, channel);
                    if (result == null) {
                        result = send(channel, alarm);
                        log(channel, result, alarm);
                        if (!"failed".equals(result.get("status"))) {
                            remember(tenant, alarmId, channel, result);
                        }
                    }
                    return result;
                })))
                .toList();

        List<Map<String, Object>> results = new ArrayList<>();
        int failed = 0;
        for (var future : futures) {
            try {
                Map<String, Object> result = future.join();
                if ("failed".equals(result.get("status"))) {
                    failed++;
                }
                results.add(result);
            } catch (Exception ex) {
                failed++;
                results.add(Map.of("status", "failed", "error", ex.getMessage()));
            }
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
        if ("LOG".equals(channel.type())) {
            result.put("status", "logged");
            result.put("detail", "Notification recorded locally; no external connector invoked");
            return result;
        }
        if ("EMAIL".equals(channel.type())) {
            if (smtpSender == null) {
                result.put("status", "failed");
                result.put("errorCode", "SMTP_CONNECTOR_UNAVAILABLE");
                result.put("detail", "SMTP notification connector is not available");
                return result;
            }
            SmtpNotificationSender.DeliveryResult delivery = smtpSender.send(
                    channel.target(), "SOCP security alarm: " + alarm.getOrDefault("id", "unknown"),
                    imText(alarm));
            result.put("status", delivery.sent() ? "sent" : "failed");
            result.put("detail", delivery.detail());
            if (!delivery.sent()) result.put("errorCode", delivery.errorCode());
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
        Object title = alarm.get("title");
        if (title == null || String.valueOf(title).isBlank()) {
            title = alarm.getOrDefault("ruleName", alarm.getOrDefault("ruleId", "Alarm"));
        }
        return "[" + severity + "] " + title + mitre
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
        try {
            NotificationDispatchLogEntity row = new NotificationDispatchLogEntity();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(tenant());
            row.setAlarmId(String.valueOf(alarm.get("id")));
            row.setChannelName(channel.name());
            row.setChannelType(channel.type());
            row.setStatus(String.valueOf(result.getOrDefault("status", "unknown")));
            row.setResultJson(MAPPER.writeValueAsString(entry));
            row.setCreatedAt(Instant.now());
            dispatchLogs.save(row);
        } catch (Exception persistenceFailure) {
            log.warn("notification dispatch log persistence failed alarmId={}: {}",
                    alarm.get("id"), persistenceFailure.getMessage());
        }
    }

    public List<Map<String, Object>> log() {
        String tenant = tenant();
        return dispatchLogs.findTop200ByTenantIdOrderByCreatedAtDesc(tenant).stream()
                .map(NotificationDispatcher::fromLogEntity)
                .toList();
    }

    private static Map<String, Object> fromLogEntity(NotificationDispatchLogEntity row) {
        try {
            Map<String, Object> out = new LinkedHashMap<>(MAPPER.readValue(row.getResultJson(), MAP_TYPE));
            out.put("channel", row.getChannelName());
            out.put("type", row.getChannelType());
            out.put("alarmId", row.getAlarmId());
            out.put("status", row.getStatus());
            out.put("tenantId", row.getTenantId());
            out.put("ts", row.getCreatedAt().toString());
            return out;
        } catch (Exception corruptLog) {
            return Map.of("alarmId", row.getAlarmId(), "status", row.getStatus(),
                    "error", "invalid persisted dispatch log");
        }
    }

    private static String deliveryId(String tenant, String alarmId, String channelId) {
        String key = tenant + "\u0000" + alarmId + "\u0000" + channelId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String tenant() {
        return TenantContext.require();
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
