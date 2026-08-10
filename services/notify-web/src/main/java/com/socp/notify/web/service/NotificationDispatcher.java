package com.socp.notify.web.service;

import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.store.ChannelStore;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 告警外发分发器：收到告警后推送到所有启用渠道。
 * WEBHOOK/SLACK 走真实 HTTP POST；EMAIL 仅记录（演示环境无 SMTP）。
 * 分发结果写入 dispatchLog 供前端展示。
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final ChannelStore channels;
    private final SocpHttpClient http;
    private final List<Map<String, Object>> dispatchLog = new CopyOnWriteArrayList<>();
    private static final int TIMEOUT = 3000;

    public NotificationDispatcher(ChannelStore channels, SocpHttpClient http) {
        this.channels = channels;
        this.http = http;
    }

    /** 接收告警（来自 alert-web）并分发。返回本次分发明细。 */
    public Map<String, Object> dispatch(Map<String, Object> alarm) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Channel ch : channels.enabled()) {
            Map<String, Object> r = send(ch, alarm);
            results.add(r);
            log(ch, r, alarm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alarmId", alarm.get("id"));
        out.put("ruleId", alarm.get("ruleId"));
        out.put("dispatched", results.size());
        out.put("results", results);
        return out;
    }

    private Map<String, Object> send(Channel ch, Map<String, Object> alarm) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("channel", ch.name());
        r.put("type", ch.type());
        if ("EMAIL".equals(ch.type())) {
            r.put("status", "logged");
            r.put("detail", "邮件已记入分发日志（演示未启 SMTP）-> " + ch.target());
            return r;
        }
        String json = buildPayload(ch, alarm);
        // 渠道地址由用户配置（可能是外网 webhook），统一走 socp-client：超时受控、失败留痕
        ServiceCall call = http.postExternal(ch.target(), json, SocpHttpClient.JSON, TIMEOUT);
        r.put("status", call.ok() ? "sent" : "failed");
        r.put("httpStatus", call.status());
        r.put("detail", call.ok()
                ? truncate(call.body(), 300)
                : truncate(call.failureReason() + " | " + call.body(), 300));
        if (!call.ok()) {
            log.warn("通知渠道分发失败 channel={} type={} target={} alarmId={} 原因={}",
                    ch.name(), ch.type(), ch.target(), alarm.get("id"), call.failureReason());
        }
        return r;
    }

    /**
     * 按渠道类型构造报文：
     * - WEBHOOK：直接透传告警原始 JSON（工单/案件系统可直接消费）
     * - SLACK/DINGTALK/WECOM：IM 文本卡片 {"text": "..."}
     * - 其它：信封形式 {channel,type,alarm}
     */
    private static String buildPayload(Channel ch, Map<String, Object> alarm) {
        String type = ch.type() == null ? "" : ch.type().toUpperCase();
        switch (type) {
            case "WEBHOOK" -> {
                return toJson(alarm);
            }
            case "SLACK", "DINGTALK", "WECOM", "WECHAT" -> {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("text", imText(alarm));
                return toJson(im);
            }
            default -> {
                Map<String, Object> env = new LinkedHashMap<>();
                env.put("channel", ch.name());
                env.put("type", ch.type());
                env.put("alarm", alarm);
                return toJson(env);
            }
        }
    }

    private static String imText(Map<String, Object> a) {
        String sev = String.valueOf(a.getOrDefault("severity", "-"));
        String mitre = a.get("mitre") == null ? "" : " [" + a.get("mitre") + "]";
        return "【" + sev + "】" + a.getOrDefault("ruleName", a.getOrDefault("ruleId", "告警")) + mitre
                + "\n实体：" + a.getOrDefault("entity", "-")
                + "\n详情：" + a.getOrDefault("message", "-")
                + "\n时间：" + a.getOrDefault("occurredAt", "-")
                + "\n告警ID：" + a.getOrDefault("id", "-");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void log(Channel ch, Map<String, Object> r, Map<String, Object> alarm) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("channel", ch.name());
        entry.put("type", ch.type());
        entry.put("alarmId", alarm.get("id"));
        entry.put("ruleId", alarm.get("ruleId"));
        entry.put("status", r.get("status"));
        if (dispatchLog.size() > 200) dispatchLog.remove(0);
        dispatchLog.add(entry);
    }

    public List<Map<String, Object>> log() {
        return List.copyOf(dispatchLog);
    }

    /** 递归 JSON 序列化：正确处理嵌套 Map/List/数值/布尔，避免把 Map 的 Java toString 当成 JSON。 */
    private static String toJson(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
