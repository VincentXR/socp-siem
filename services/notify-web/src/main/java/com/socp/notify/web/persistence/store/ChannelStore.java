package com.socp.notify.web.persistence.store;



import com.socp.notify.web.persistence.store.*;
import com.socp.notify.web.persistence.repository.*;
import com.socp.notify.web.persistence.entity.*;
import com.socp.notify.web.domain.Channel;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.socp.platform.tenant.context.TenantContext;

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
    private final boolean demoDataEnabled;

    public ChannelStore(ChannelRepository repository) {
        this(repository, true);
    }

    @Autowired
    public ChannelStore(ChannelRepository repository,
                        @Value("${socp.demo-data.enabled:true}") boolean demoDataEnabled) {
        this.repository = repository;
        this.demoDataEnabled = demoDataEnabled;
    }

    @PostConstruct
    void seed() {
        if (!demoDataEnabled) return;
        TenantContext.set("default");
        List<ChannelEntity> all = repository.findByTenantId("default");
        if (all.isEmpty()) {
            add(Channel.of("值班群(Slack)", "SLACK",
                    "https://hooks.slack.com/services/T000/B000/XXXXXXXX", true, "安全值班 IM 群"));
            add(Channel.of("工单系统(Webhook)", "WEBHOOK",
                    "http://localhost:18097/incident-web/api/v1/incidents/from-alarm", true, "推送至案件系统建案"));
            add(Channel.of("安全邮件", "EMAIL", "soc@example.com", false, "邮件摘要（演示未启 SMTP）"));
        }
        TenantContext.clear();
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
        return TenantContext.require();
    }
}
