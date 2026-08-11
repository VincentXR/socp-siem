package com.socp.notify.web.store;

import com.socp.notify.web.domain.Channel;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 通知渠道存储——内存 + H2 双写（t_channel）：启动从库恢复，写操作同步落库，重启不丢。
 * 生产对接 ServiceNow/Jira/钉钉/飞书/Slack 等连接器。
 */
@Component
public class ChannelStore {

    private final ChannelRepository repository;
    private final Map<String, Channel> byId = new ConcurrentHashMap<>();
    private final List<Channel> order = new CopyOnWriteArrayList<>();

    public ChannelStore(ChannelRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void seed() {
        List<ChannelEntity> all = repository.findAll();
        if (all.isEmpty()) {
            add(Channel.of("值班群(Slack)", "SLACK",
                    "https://hooks.slack.com/services/T000/B000/XXXXXXXX", true, "安全值班 IM 群"));
            add(Channel.of("工单系统(Webhook)", "WEBHOOK",
                    "http://localhost:18097/incident-web/api/v1/incidents/from-alarm", true, "推送至案件系统建案"));
            add(Channel.of("安全邮件", "EMAIL", "soc@example.com", false, "邮件摘要（演示未启 SMTP）"));
        } else {
            for (ChannelEntity e : all) {
                Channel c = new Channel(e.getId(), e.getName(), e.getType(), e.getTarget(), e.isEnabled(), e.getDescription());
                byId.put(c.id(), c);
                order.add(c);
            }
        }
    }

    public synchronized Channel add(Channel ch) {
        byId.put(ch.id(), ch);
        order.removeIf(c -> c.id().equals(ch.id()));
        order.add(ch);
        repository.save(new ChannelEntity(ch.id(), ch.name(), ch.type(), ch.target(), ch.enabled(), ch.description()));
        return ch;
    }

    public List<Channel> list() {
        return List.copyOf(order);
    }

    public Channel get(String id) {
        return byId.get(id);
    }

    public boolean delete(String id) {
        Channel removed = byId.remove(id);
        if (removed != null) order.remove(removed);
        try {
            repository.deleteById(id);
        } catch (Exception ignored) { }
        return removed != null;
    }

    public List<Channel> enabled() {
        return order.stream().filter(Channel::enabled).toList();
    }
}
