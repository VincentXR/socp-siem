package com.socp.attack.web.persistence.repository;
import com.socp.attack.web.persistence.entity.TechniqueNoteEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import java.util.Optional;
public interface TechniqueNoteRepository extends TenantScopedRepository<TechniqueNoteEntity, String> {
    Optional<TechniqueNoteEntity> findByTenantIdAndTechniqueId(String tenantId, String techniqueId);
}
