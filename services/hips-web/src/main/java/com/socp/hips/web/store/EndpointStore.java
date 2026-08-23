package com.socp.hips.web.store;

import com.socp.hips.web.model.Endpoint;
import com.socp.platform.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
                map.put(key(e.getTenantId(), e.getEndpointId()), new Endpoint(e.getEndpointId(), e.getHostname(), e.getIp(), e.getOs(),
                        e.getAgentVersion(), e.getStatus(), e.getLastHeartbeat()));
            }
        }
    }

    public List<Endpoint> list() {
        String prefix = tenant() + "|";
        return map.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue).toList();
    }

    public Endpoint save(Endpoint e) {
        String tenant = tenant();
        map.put(key(tenant, e.id()), e);
        repository.save(toEntity(e, tenant));
        return e;
    }

    public Endpoint heartbeat(String id) {
        String tenant = tenant();
        Endpoint e = map.get(key(tenant, id));
        if (e == null) return null;
        Endpoint updated = new Endpoint(e.id(), e.hostname(), e.ip(), e.os(), e.agentVersion(),
                "ONLINE", Instant.now());
        map.put(key(tenant, id), updated);
        repository.save(toEntity(updated, tenant));
        return updated;
    }

    public boolean delete(String id) {
        String tenant = tenant();
        boolean removed = map.remove(key(tenant, id)) != null;
        repository.findByTenantIdAndEndpointId(tenant, id).ifPresent(repository::delete);
        return removed;
    }

    private static EndpointEntity toEntity(Endpoint e, String tenant) {
        return new EndpointEntity(storageId(tenant, e.id()), e.id(), tenant,
                e.hostname(), e.ip(), e.os(), e.agentVersion(),
                e.status(), e.lastHeartbeat());
    }

    private static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }

    private static String key(String tenant, String id) {
        return tenant + "|" + id;
    }

    private static String storageId(String tenant, String id) {
        return UUID.nameUUIDFromBytes(key(tenant, id).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
