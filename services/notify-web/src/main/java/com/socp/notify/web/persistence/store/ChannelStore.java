package com.socp.notify.web.persistence.store;


import com.socp.notify.web.persistence.repository.ChannelRepository;
import com.socp.notify.web.persistence.entity.ChannelEntity;
import com.socp.notify.web.domain.Channel;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.socp.platform.tenant.context.TenantContext;

import java.util.List;
import com.socp.platform.error.exception.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Tenant-scoped database channel catalogue with serialized quota admission.
 */
@Component
public class ChannelStore {

    private final ChannelRepository repository;
    private final boolean demoDataEnabled;
    private final ChannelCoordinator coordinator;
    public static final int MAX_CHANNELS = 64;
    public static final int MAX_ENABLED = 16;

    @Autowired
    public ChannelStore(ChannelRepository repository, ChannelCoordinator coordinator,
                        @Value("${socp.demo-data.enabled:true}") boolean demoDataEnabled) {
        this.repository = repository;
        this.demoDataEnabled = demoDataEnabled;
        this.coordinator = coordinator;
    }

    @PostConstruct
    void seed() {
        if (!demoDataEnabled) return;
        TenantContext.runWith("default", () -> coordinator.mutate(() -> {
            if (repository.countByTenantId("default") == 0) {
                add(Channel.of("值班群(Slack)", "SLACK",
                        "https://hooks.slack.com/services/T000/B000/XXXXXXXX", true, "安全值班 IM 群"));
                add(Channel.of("工单系统(Webhook)", "WEBHOOK",
                        "http://localhost:18097/incident-web/api/v1/incidents/from-alarm", true, "推送至案件系统建案"));
                add(Channel.of("安全邮件", "EMAIL", "soc@example.com", false, "邮件摘要（演示未启 SMTP）"));
            }
            return null;
        }));
    }

    public Channel add(Channel ch) {
        return coordinator.mutate(() -> {
            if (repository.countByTenantId(tenant()) >= MAX_CHANNELS) {
                throw ApiException.of(409, "每个租户最多配置 " + MAX_CHANNELS + " 个通知渠道");
            }
            checkEnabled(ch.enabled(), false);
            ChannelEntity entity = new ChannelEntity(ch.id(), ch.name(), ch.type(), ch.target(), ch.enabled(), ch.description());
            entity.setTenantId(tenant());
            repository.saveAndFlush(entity);
            return ch;
        });
    }

    public Page<Channel> list(int page, int size) {
        return repository.findByTenantIdOrderByNameAscIdAsc(tenant(), PageRequest.of(page - 1, size)).map(ChannelStore::fromEntity);
    }

    public long count() { return repository.countByTenantId(tenant()); }

    public Channel update(Channel ch) {
        return coordinator.mutate(() -> {
            var entity = require(ch.id());
            checkEnabled(ch.enabled(), entity.isEnabled());
            entity.update(ch.name(), ch.type(), ch.target(), ch.enabled(), ch.description());
            repository.saveAndFlush(entity);
            return fromEntity(entity);
        });
    }

    public Channel toggle(String id) {
        return coordinator.mutate(() -> {
            var entity = require(id);
            checkEnabled(!entity.isEnabled(), entity.isEnabled());
            entity.update(entity.getName(), entity.getType(), entity.getTarget(), !entity.isEnabled(), entity.getDescription());
            repository.saveAndFlush(entity);
            return fromEntity(entity);
        });
    }

    private ChannelEntity require(String id) {
        return repository.findByIdAndTenantId(id, tenant()).orElseThrow(() -> ApiException.notFound("未找到通知渠道 " + id));
    }

    private void checkEnabled(boolean enabled, boolean wasEnabled) {
        if (enabled && !wasEnabled && repository.countByTenantIdAndEnabledTrue(tenant()) >= MAX_ENABLED) {
            throw ApiException.of(409, "每个租户最多启用 " + MAX_ENABLED + " 个通知渠道");
        }
    }

    public Channel get(String id) {
        return repository.findByIdAndTenantId(id, tenant()).map(ChannelStore::fromEntity).orElse(null);
    }

    public boolean delete(String id) {
        return coordinator.mutate(() -> {
            var entity = repository.findByIdAndTenantId(id, tenant());
            if (entity.isEmpty()) return false;
            repository.delete(entity.get());
            repository.flush();
            return true;
        });
    }

    public List<Channel> enabled() {
        var enabled = repository.findByTenantIdAndEnabledTrueOrderByIdAsc(tenant(), PageRequest.of(0, MAX_ENABLED + 1));
        if (enabled.size() > MAX_ENABLED) throw ApiException.of(409, "启用通知渠道超过 " + MAX_ENABLED + " 个，请先禁用多余渠道");
        return enabled.stream().map(ChannelStore::fromEntity).toList();
    }

    private static Channel fromEntity(ChannelEntity entity) {
        return new Channel(entity.getId(), entity.getName(), entity.getType(), entity.getTarget(),
                entity.isEnabled(), entity.getDescription());
    }

    private static String tenant() {
        return TenantContext.require();
    }
}
