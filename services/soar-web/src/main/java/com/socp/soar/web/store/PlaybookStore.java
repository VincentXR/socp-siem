package com.socp.soar.web.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.TenantContext;
import com.socp.soar.web.model.Playbook;
import com.socp.soar.web.model.PlaybookStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 剧本存储——JPA + H2 文件库（Flyway V1 建表），重启不丢；接口与原内存版一致。
 * 种子剧本仅在空库时写入；actions 以 JSON 字符串持久化。
 */
@Component
public class PlaybookStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {
    };

    private final PlaybookRepository repo;
    private final boolean demoDataEnabled;

    public PlaybookStore(PlaybookRepository repo) {
        this(repo, true);
    }

    @Autowired
    public PlaybookStore(PlaybookRepository repo,
                         @Value("${socp.demo-data.enabled:true}") boolean demoDataEnabled) {
        this.repo = repo;
        this.demoDataEnabled = demoDataEnabled;
        if (demoDataEnabled && repo.countByTenantId("default") == 0) {
            seed();
        }
    }

    private String tenant() {
        String t = TenantContext.get();
        return t == null ? "default" : t;
    }

    private void seed() {
        save(Playbook.create("高危告警自动封禁", "告警 severity >= HIGH 且实体为 IP",
                List.of("simulate:查询资产归属", "simulate:下发防火墙封禁", "通知值班群", "写入事件单"), true));
        save(Playbook.create("暴力破解隔离主机", "AUTH-BRUTE-SUCCESS 关联告警",
                List.of("simulate:标记主机失陷", "simulate:网络隔离 (VLAN 迁移)", "simulate:快照取证"), true));
        save(Playbook.create("每日安全巡检", "定时 每天 03:00",
                List.of("simulate:汇总告警", "simulate:生成日报", "simulate:邮件推送"), false));
        save(Playbook.create("Webhook 联动演示", "告警 severity >= HIGH",
                List.of("simulate:记录研判上下文",
                        "http://localhost:18097/incident-web/api/v1/incidents/from-alarm"),
                true));
    }

    public List<Playbook> list() {
        return repo.findByTenantId(tenant()).stream().map(PlaybookStore::fromEntity).toList();
    }

    public Playbook save(Playbook pb) {
        repo.save(toEntity(pb, tenant()));
        return pb;
    }

    public boolean delete(String id) {
        Optional<PlaybookEntity> e = repo.findByIdAndTenantId(id, tenant());
        if (e.isEmpty()) return false;
        repo.delete(e.get());
        return true;
    }

    public Playbook get(String id) {
        return repo.findByIdAndTenantId(id, tenant()).map(PlaybookStore::fromEntity).orElse(null);
    }

    public Playbook toggle(String id) {
        Optional<PlaybookEntity> e = repo.findByIdAndTenantId(id, tenant());
        if (e.isEmpty()) return null;
        PlaybookEntity ent = e.get();
        boolean enabled = !ent.isEnabled();
        ent.setEnabled(enabled);
        ent.setStatus(enabled ? PlaybookStatus.ACTIVE.name() : PlaybookStatus.DRAFT.name());
        repo.save(ent);
        return fromEntity(ent);
    }

    private static Playbook fromEntity(PlaybookEntity e) {
        List<String> actions;
        try {
            actions = MAPPER.readValue(e.getActions(), STR_LIST);
        } catch (Exception ex) {
            actions = List.of();
        }
        PlaybookStatus status;
        try {
            status = PlaybookStatus.valueOf(e.getStatus());
        } catch (Exception ex) {
            status = e.isEnabled() ? PlaybookStatus.ACTIVE : PlaybookStatus.DRAFT;
        }
        return new Playbook(e.getId(), e.getName(), e.getTrigger(), actions, e.isEnabled(),
                status, e.getCreatedAt());
    }

    private static PlaybookEntity toEntity(Playbook p, String tenant) {
        PlaybookEntity e = new PlaybookEntity();
        e.setId(p.id());
        e.setName(p.name());
        e.setTrigger(p.trigger());
        try {
            e.setActions(MAPPER.writeValueAsString(p.actions()));
        } catch (Exception ex) {
            e.setActions("[]");
        }
        e.setEnabled(p.enabled());
        e.setStatus(p.status() == null ? PlaybookStatus.DRAFT.name() : p.status().name());
        e.setCreatedAt(p.createdAt() == null ? Instant.now() : p.createdAt());
        e.setTenantId(tenant);
        return e;
    }
}
