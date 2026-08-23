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
    public ChannelStore(ChannelRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void seed() {
        List<ChannelEntity> all = repository.findByTenantId("default");
        if (all.isEmpty()) {
            add(Channel.of("值班群(Slack)", "SLACK",
                    "https://hooks.slack.com/services/T000/B000/XXXXXXXX", true, "安全值班 IM 群"));
            add(Channel.of("工单系统(Webhook)", "WEBHOOK",
                    "http://localhost:18097/incident-web/api/v1/incidents/from-alarm", true, "推送至案件系统建案"));
            add(Channel.of("安全邮件", "EMAIL", "soc@example.com", false, "邮件摘要（演示未启 SMTP）"));
        }
    }

    public synchronized Channel add(Channel ch) {
        ChannelEntity entity = new ChannelEntity(
                ch.id(), ch.name(), ch.type(), ch.target(), ch.enabled(), ch.description());
        entity.setTenantId(tenant());
        repository.save(entity);
        return ch;
    }

    public List<Channel> list() {
        return repository.findByTenantId(tenant()).stream().map(ChannelStore::fromEntity).toList();
    }

    public Channel get(String id) {
        return repository.findByIdAndTenantId(id, tenant()).map(ChannelStore::fromEntity).orElse(null);
    }

    public boolean delete(String id) {
        var entity = repository.findByIdAndTenantId(id, tenant());
        if (entity.isEmpty()) return false;
        repository.delete(entity.get());
        return true;
    }

    public List<Channel> enabled() {
        return list().stream().filter(Channel::enabled).toList();
    }

    private static Channel fromEntity(ChannelEntity entity) {
        return new Channel(entity.getId(), entity.getName(), entity.getType(), entity.getTarget(),
                entity.isEnabled(), entity.getDescription());
    }

    private static String tenant() {
        String tenant = com.socp.platform.tenant.TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }
}
