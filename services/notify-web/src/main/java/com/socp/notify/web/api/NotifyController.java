package com.socp.notify.web.api;

import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.service.NotificationDispatcher;
import com.socp.notify.web.store.ChannelStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.socp.platform.auth.RequireRole;

/**
 * 通知与集成 REST API（context-path /notify-web）。
 * 渠道 CRUD + 告警外发入口（由 alert-web 在创建告警时调用）。
 */
@RestController
@RequestMapping("/api/v1")
public class NotifyController {

    private final ChannelStore channels;
    private final NotificationDispatcher dispatcher;

    public NotifyController(ChannelStore channels, NotificationDispatcher dispatcher) {
        this.channels = channels;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/channels")
    public List<Channel> channels() {
        return channels.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/channels")
    public Channel create(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> _ignore = null;
        Channel ch = Channel.of(
                str(body, "name"), str(body, "type"), str(body, "target"),
                bool(body, "enabled", true), str(body, "description"));
        return channels.add(ch);
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/channels/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable String id) {
        Channel ch = channels.get(id);
        if (ch == null) return Map.of("error", "not_found");
        Channel updated = new Channel(ch.id(), ch.name(), ch.type(), ch.target(), !ch.enabled(), ch.description());
        channels.add(updated);
        return Map.of("channel", updated);
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/channels/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", channels.delete(id), "id", id);
    }

    /** 告警外发入口：接收 alert-web 推送的告警，分发到启用渠道。 */
    @PostMapping("/notify/alert")
    public Map<String, Object> notify(@RequestBody Map<String, Object> alarm) {
        return dispatcher.dispatch(alarm);
    }

    @GetMapping("/dispatch-log")
    public List<Map<String, Object>> log() {
        return dispatcher.log();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean bool(Map<String, Object> m, String k, boolean d) {
        Object v = m.get(k);
        return v == null ? d : Boolean.parseBoolean(String.valueOf(v));
    }
}
