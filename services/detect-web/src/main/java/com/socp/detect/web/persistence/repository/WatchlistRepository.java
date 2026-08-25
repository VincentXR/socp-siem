package com.socp.detect.web.persistence.repository;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistRepository extends JpaRepository<WatchlistEntity, Long> {

    Optional<WatchlistEntity> findByTenantIdAndListName(String tenantId, String listName);

    List<WatchlistEntity> findByTenantId(String tenantId);
}
