package com.socp.notify.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.persistence.store.ChannelStore;
import com.socp.notify.web.persistence.entity.NotificationDispatchLogEntity;
import com.socp.notify.web.persistence.repository.NotificationDispatchLogRepository;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.tenant.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Dispatches an alarm to enabled channels with per-alarm/channel idempotency. */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final int TIMEOUT = 3000;

    private final ChannelStore channels;
    private final SocpHttpClient http;
    private final NotificationDeliveryState deliveries;
    private final NotificationExecutor executor;
    private final NotificationDispatchLogRepository dispatchLogs;
    private final SmtpNotificationSender smtpSender;
    public NotificationDispatcher(ChannelStore channels, SocpHttpClient http,
                                  NotificationDeliveryState deliveries,
                                  NotificationDispatchLogRepository dispatchLogs,
                                  SmtpNotificationSender smtpSender, NotificationExecutor executor) {
        this.channels = channels;
        this.http = http;
        this.deliveries = deliveries;
        this.dispatchLogs = dispatchLogs;
        this.smtpSender = smtpSender;
        this.executor = executor;
    }

    public Map<String, Object> dispatch(Map<String, Object> alarm) {
        String alarmId = text(alarm.get("id"));
        if (alarmId == null || alarmId.length() > 255) throw new IllegalArgumentException("valid alarm id is required");
        try {
            if (MAPPER.writeValueAsBytes(alarm).length > 256 * 1024) {
                throw com.socp.platform.error.exception.ApiException.of(413, "notification payload exceeds 256 KiB");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw com.socp.platform.error.exception.ApiException.badRequest("notification payload cannot be serialized");
        }
        String tenant = tenant();
        List<Channel> enabledChannels = channels.enabled();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (Channel channel : enabledChannels) {
            try {
                futures.add(executor.submit(TenantContext.wrap(tenant, () -> deliver(alarmId, channel, alarm))));
            } catch (java.util.concurrent.RejectedExecutionException saturated) {
                futures.add(CompletableFuture.completedFuture(failed(channel, "NOTIFY_BUSY", "Notification capacity is busy; retry later")));
            }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int failed = 0;
        for (int index = 0; index < futures.size(); index++) {
            var result = await(futures.get(index), enabledChannels.get(index), deadline);
            if ("failed".equals(result.get("status"))) failed++;
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

    /** Explicit operator test, restricted to one selected channel, with a distinct test identity. */
    public Map<String, Object> test(Channel channel) {
        Map<String, Object> sample = Map.of("id", "test-" + UUID.randomUUID(), "ruleId", "notification-test",
                "severity", "INFO", "message", "SOCP notification test", "test", true);
        String tenant = tenant();
        try {
            var result = await(executor.submit(TenantContext.wrap(tenant, () -> {
                var testResult = send(channel, sample);
                log(channel, testResult, sample);
                return testResult;
            })), channel, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            if ("NOTIFY_PENDING".equals(result.get("errorCode"))) {
                return failed(channel, "NOTIFY_TEST_UNCONFIRMED", "Test may still complete; inspect dispatch history or the destination before another test");
            }
            return result;
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            return failed(channel, "NOTIFY_BUSY", "Notification capacity is busy; retry later");
        }
    }

    private Map<String, Object> deliver(String alarmId, Channel channel, Map<String, Object> alarm) {
        var claim = deliveries.claim(alarmId, channel.id());
        if (claim.receiptJson() != null) {
            try {
                var result = new LinkedHashMap<>(MAPPER.readValue(claim.receiptJson(), MAP_TYPE));
                if (!List.of("sent", "logged").contains(result.get("status"))) throw new IllegalStateException();
                result.put("duplicate", true);
                return result;
            } catch (Exception corrupt) {
                return failed(channel, "NOTIFY_RECEIPT_INVALID", "Invalid notification delivery receipt; inspect durable state");
            }
        }
        if (claim.token() == null) return failed(channel, "NOTIFY_PENDING", "Delivery is in progress or awaiting retry");
        Map<String, Object> result;
        try {
            result = send(channel, alarm);
        } catch (RuntimeException connectorFailure) {
            result = failed(channel, "NOTIFY_CONNECTOR_FAILED", "Notification connector failed; remote acceptance may be unknown");
        }
        try {
            if (!deliveries.finish(alarmId, channel.id(), claim.token(), MAPPER.writeValueAsString(result),
                    !"failed".equals(result.get("status")))) {
                return failed(channel, "NOTIFY_CLAIM_LOST", "Delivery ownership changed; retry to read the durable result");
            }
        } catch (Exception persistenceFailure) {
            return failed(channel, "NOTIFY_RECEIPT_UNCONFIRMED", "Notification receipt could not be confirmed; remote acceptance may be unknown");
        }
        log(channel, result, alarm);
        return result;
    }

    private static Map<String, Object> await(CompletableFuture<Map<String, Object>> future, Channel channel, long deadline) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(channel, "NOTIFY_PENDING", "Delivery continues; retry to read the durable result");
        } catch (java.util.concurrent.TimeoutException timeout) {
            return failed(channel, "NOTIFY_PENDING", "Delivery continues; retry to read the durable result");
        } catch (java.util.concurrent.ExecutionException failure) {
            return failed(channel, "NOTIFY_UNAVAILABLE", "Notification state is unavailable; retry later");
        }
    }

    private static Map<String, Object> failed(Channel channel, String code, String detail) {
        return Map.of("channel", channel.name(), "channelId", channel.id(), "type", channel.type(),
                "status", "failed", "errorCode", code, "detail", detail);
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
        ServiceCall call = http.postExternalOnce(channel.target(), buildPayload(channel, alarm),
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
            log.warn("Notification channel failed channelId={} type={} alarmId={} httpStatus={}",
                    channel.id(), channel.type(), alarm.get("id"), call.status());
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
