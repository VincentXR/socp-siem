package com.socp.hips.web.store;

import com.socp.hips.web.model.Endpoint;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 端点注册表——内存 + H2 双写（t_endpoint）：启动从库恢复，写操作同步落库，重启不丢。
 * 接口不变；生产可换 PG（同 schema）。
 */
@Component
public class EndpointStore {

    private final EndpointRepository repository;
    private final ConcurrentHashMap<String, Endpoint> map = new ConcurrentHashMap<>();

    public EndpointStore(EndpointRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void init() {
        List<EndpointEntity> all = repository.findAll();
        if (all.isEmpty()) {
            save(Endpoint.register("web01", "10.0.0.5", "Ubuntu 22.04", "falco-0.39"));
            save(Endpoint.register("web02", "10.0.0.6", "Ubuntu 22.04", "falco-0.39"));
            save(Endpoint.register("db-master", "10.0.0.10", "Debian 12", "falco-0.38"));
        } else {
            for (EndpointEntity e : all) {
                map.put(e.getId(), new Endpoint(e.getId(), e.getHostname(), e.getIp(), e.getOs(),
                        e.getAgentVersion(), e.getStatus(), e.getLastHeartbeat()));
            }
        }
    }

    public List<Endpoint> list() {
        return map.values().stream().toList();
    }

    public Endpoint save(Endpoint e) {
        map.put(e.id(), e);
        repository.save(toEntity(e));
        return e;
    }

    public Endpoint heartbeat(String id) {
        Endpoint e = map.get(id);
        if (e == null) return null;
        Endpoint updated = new Endpoint(e.id(), e.hostname(), e.ip(), e.os(), e.agentVersion(),
                "ONLINE", Instant.now());
        map.put(id, updated);
        repository.save(toEntity(updated));
        return updated;
    }

    public boolean delete(String id) {
        boolean removed = map.remove(id) != null;
        try {
            repository.deleteById(id);
        } catch (Exception ignored) { }
        return removed;
    }

    private static EndpointEntity toEntity(Endpoint e) {
        return new EndpointEntity(e.id(), e.hostname(), e.ip(), e.os(), e.agentVersion(),
                e.status(), e.lastHeartbeat());
    }
}
