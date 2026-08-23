package com.socp.soc.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface TenantRepository extends JpaRepository<TenantEntity, String> {

    Optional<TenantEntity> findByCode(String code);

    List<TenantEntity> findAllByOrderByCodeAsc();
}
