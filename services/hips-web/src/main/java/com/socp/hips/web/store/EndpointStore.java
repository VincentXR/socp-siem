package com.socp.hips.web.store;

import com.socp.hips.web.model.Endpoint;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 端点注册表——进程内；生产替换为 PG hips.t_endpoint，接口不变。
 */
@Component
public class EndpointStore {

    private final ConcurrentHashMap<String, Endpoint> map = new ConcurrentHashMap<>();

    public EndpointStore() {
        save(Endpoint.register("web01", "10.0.0.5", "Ubuntu 22.04", "falco-0.39"));
        save(Endpoint.register("web02", "10.0.0.6", "Ubuntu 22.04", "falco-0.39"));
        save(Endpoint.register("db-master", "10.0.0.10", "Debian 12", "falco-0.38"));
    }

    public List<Endpoint> list() {
        return map.values().stream().toList();
    }

    public Endpoint save(Endpoint e) {
        map.put(e.id(), e);
        return e;
    }

    public Endpoint heartbeat(String id) {
        Endpoint e = map.get(id);
        if (e == null) return null;
        Endpoint updated = new Endpoint(e.id(), e.hostname(), e.ip(), e.os(), e.agentVersion(),
                "ONLINE", java.time.Instant.now());
        map.put(id, updated);
        return updated;
    }

    public boolean delete(String id) {
        return map.remove(id) != null;
    }
}
