package com.socp.ai.persistence.repository;


import com.socp.ai.persistence.entity.QaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QaRepository extends JpaRepository<QaEntity, String> {

    @Query("select q from QaEntity q where locate(q.keyword, :question) > 0 order by length(q.keyword) desc")
    List<QaEntity> findMatches(@Param("question") String question, Pageable pageable);
}
