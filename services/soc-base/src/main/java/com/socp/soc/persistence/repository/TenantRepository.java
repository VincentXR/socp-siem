package com.socp.soc.persistence.repository;



import com.socp.soc.persistence.store.*;
import com.socp.soc.persistence.repository.*;
import com.socp.soc.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<TenantEntity, String> {

    Optional<TenantEntity> findByCode(String code);

    List<TenantEntity> findAllByOrderByCodeAsc();
}
