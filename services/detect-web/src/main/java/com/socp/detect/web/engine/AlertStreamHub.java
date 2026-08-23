package com.socp.detect.web.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.rule.model.Alert;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 告警实时流（SSE）：{@link RecentAlertSink} 每产出一条告警就广播给所有订阅的前端连接，
 * 实时态势大屏据此即时刷新，无需轮询。
 *
 * <p>实现采用 Servlet 输出流直写（controller 阻塞 + 3s 心跳），规避 SseEmitter 在
 * 本环境异步初始化不生效的问题；客户端断开时写失败自动摘除。
 * Alert 含 Instant 字段，序列化必须注册 JavaTimeModule（裸 ObjectMapper 会抛异常）。
 */
@Component
public class AlertStreamHub {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private record Subscriber(String tenant, PrintWriter writer) {}
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public void add(PrintWriter w) {
        add("default", w);
    }

    public void add(String tenant, PrintWriter w) {
        subscribers.add(new Subscriber(tenant, w));
    }

    public void remove(PrintWriter w) {
        subscribers.removeIf(subscriber -> subscriber.writer() == w);
    }

    /** 广播一条告警给所有订阅者；单个写失败即摘除该连接，不影响其他订阅者。 */
    public void broadcast(Alert alert) {
        broadcast("default", alert);
    }

    public void broadcast(String tenant, Alert alert) {
        if (subscribers.isEmpty()) return;
        String json;
        try {
            json = MAPPER.writeValueAsString(alert);
        } catch (Exception e) {
            return;
        }
        String frame = "event: alert\ndata: " + json + "\n\n";
        for (Subscriber subscriber : subscribers) {
            if (!subscriber.tenant().equals(tenant)) continue;
            PrintWriter w = subscriber.writer();
            try {
                w.write(frame);
                w.flush();
            } catch (Exception e) {
                subscribers.remove(subscriber);
            }
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
