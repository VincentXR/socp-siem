package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.DetectionRouteTopologyEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;

public interface DetectionRouteTopologyRepository
        extends TenantScopedRepository<DetectionRouteTopologyEntity, String> {

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "insert into t_detection_route_topology "
            + "(id, tenant_id, routing_version, plan_version, created_at) values "
            + "(:id, :tenant, :version, :plan, :created)", nativeQuery = true)
    int insertUnbound(@org.springframework.data.repository.query.Param("id") String id,
                      @org.springframework.data.repository.query.Param("tenant") String tenant,
                      @org.springframework.data.repository.query.Param("version") String version,
                      @org.springframework.data.repository.query.Param("plan") String plan,
                      @org.springframework.data.repository.query.Param("created") java.time.Instant created);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from DetectionRouteTopologyEntity e "
            + "where e.tenantId = :tenant and e.routingVersion = :version")
    Optional<DetectionRouteTopologyEntity> lockByTenantAndVersion(
            @org.springframework.data.repository.query.Param("tenant") String tenant,
            @org.springframework.data.repository.query.Param("version") String version);

    Optional<DetectionRouteTopologyEntity> findByTenantIdAndRoutingVersion(
            String tenantId, String routingVersion);
}
