package com.socp.attack.web.persistence.repository;


import com.socp.attack.web.persistence.entity.TacticEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TacticRepository extends JpaRepository<TacticEntity, String> {
    java.util.List<TacticEntity> findAllByOrderBySortAsc();
}
