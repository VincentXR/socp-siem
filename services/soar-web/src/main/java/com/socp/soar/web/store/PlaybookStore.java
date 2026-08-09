package com.socp.soar.web.store;

import com.socp.soar.web.model.Playbook;
import com.socp.soar.web.model.PlaybookStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 剧本存储——进程内 ConcurrentHashMap。生产替换为 PG（soar.t_playbook），接口不变。
 * 种子剧本与前端 DEMO_PLAYBOOKS 同语义。
 */
@Component
public class PlaybookStore {

    private final ConcurrentHashMap<String, Playbook> map = new ConcurrentHashMap<>();

    public PlaybookStore() {
        seed();
    }

    private void seed() {
        save(Playbook.create("高危告警自动封禁", "告警 severity >= HIGH 且实体为 IP",
                List.of("查询资产归属", "下发防火墙封禁", "通知值班群", "写入事件单"), true));
        save(Playbook.create("暴力破解隔离主机", "AUTH-BRUTE-SUCCESS 关联告警",
                List.of("标记主机失陷", "网络隔离 (VLAN 迁移)", "快照取证"), true));
        save(Playbook.create("每日安全巡检", "定时 每天 03:00",
                List.of("汇总告警", "生成日报", "邮件推送"), false));
        save(Playbook.create("Webhook 联动演示", "告警 severity >= HIGH",
                List.of("记录研判上下文",
                        "http://localhost:18097/incident-web/api/v1/incidents/from-alarm"),
                true));
    }

    public List<Playbook> list() {
        return map.values().stream().toList();
    }

    public Playbook save(Playbook pb) {
        map.put(pb.id(), pb);
        return pb;
    }

    public boolean delete(String id) {
        return map.remove(id) != null;
    }

    public Playbook get(String id) {
        return map.get(id);
    }

    public Playbook toggle(String id) {
        Playbook pb = map.get(id);
        if (pb == null) return null;
        Playbook updated = new Playbook(pb.id(), pb.name(), pb.trigger(), pb.actions(),
                !pb.enabled(), !pb.enabled() ? PlaybookStatus.ACTIVE : PlaybookStatus.DRAFT, pb.createdAt());
        map.put(id, updated);
        return updated;
    }
}
