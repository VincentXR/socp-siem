package com.socp.attack.web.persistence.repository;


import com.socp.attack.web.persistence.entity.TechniqueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TechniqueRepository extends JpaRepository<TechniqueEntity, String> {
    java.util.List<TechniqueEntity> findAllByOrderByIdAsc();
}
