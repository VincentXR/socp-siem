package com.socp.hips.web.persistence.store;


import com.socp.hips.web.persistence.repository.EndpointRepository;
import com.socp.hips.web.persistence.entity.EndpointEntity;
import com.socp.hips.web.domain.Endpoint;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Tenant endpoint registry backed solely by {@code t_endpoint}.
 * The default demo inventory is seeded only when that tenant has no rows.
 */
@Component
public class EndpointStore {

    private final EndpointRepository repository;
    private final boolean demoDataEnabled;

    public EndpointStore(EndpointRepository repository) {
        this(repository, true);
    }

    @Autowired
    public EndpointStore(EndpointRepository repository,
                         @Value("${socp.demo-data.enabled:true}") boolean demoDataEnabled) {
        this.repository = repository;
        this.demoDataEnabled = demoDataEnabled;
    }

    @PostConstruct
    void init() {
        if (!demoDataEnabled) return;
        TenantContext.runWith("default", () -> {
            List<EndpointEntity> all = repository.findByTenantId("default");
            if (all.isEmpty()) {
                save(Endpoint.register("web01", "10.0.0.5", "Ubuntu 22.04", "falco-0.39"));
                save(Endpoint.register("web02", "10.0.0.6", "Ubuntu 22.04", "falco-0.39"));
                save(Endpoint.register("db-master", "10.0.0.10", "Debian 12", "falco-0.38"));
            }
        });
    }

    @Transactional(readOnly = true)
    public List<Endpoint> list() {
        return repository.findByTenantId(tenant()).stream().map(EndpointStore::fromEntity).toList();
    }

    @Transactional
    public Endpoint save(Endpoint e) {
        String tenant = tenant();
        repository.save(toEntity(e, tenant));
        return e;
    }

    @Transactional
    public Endpoint heartbeat(String id) {
        String tenant = tenant();
        EndpointEntity entity = repository.findByTenantIdAndEndpointId(tenant, id).orElse(null);
        if (entity == null) return null;
        entity.setStatus("ONLINE");
        entity.setLastHeartbeat(Instant.now());
        return fromEntity(repository.save(entity));
    }

    @Transactional
    public boolean delete(String id) {
        String tenant = tenant();
        var entity = repository.findByTenantIdAndEndpointId(tenant, id);
        if (entity.isEmpty()) return false;
        repository.delete(entity.get());
        return true;
    }

    private static EndpointEntity toEntity(Endpoint e, String tenant) {
        return new EndpointEntity(storageId(tenant, e.id()), e.id(), tenant,
                e.hostname(), e.ip(), e.os(), e.agentVersion(),
                e.status(), e.lastHeartbeat());
    }

    private static String tenant() {
        return TenantContext.require();
    }

    private static final java.time.Duration HEARTBEAT_EXPIRATION = java.time.Duration.ofMinutes(5);

    private static Endpoint fromEntity(EndpointEntity entity) {
        String status = entity.getStatus();
        Instant last = entity.getLastHeartbeat();
        // 动态心跳衰减判定：若超期未收到探针心跳，动态判定为 OFFLINE
        if (last != null && last.isBefore(Instant.now().minus(HEARTBEAT_EXPIRATION))) {
            status = "OFFLINE";
        }
        return new Endpoint(entity.getEndpointId(), entity.getHostname(), entity.getIp(), entity.getOs(),
                entity.getAgentVersion(), status, entity.getLastHeartbeat());
    }

    private static String storageId(String tenant, String id) {
        return UUID.nameUUIDFromBytes((tenant + "|" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
