package com.socp.threat.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 威胁情报 IOC 仓储。 */
public interface IocRepository extends JpaRepository<IocEntity, String> {

    Optional<IocEntity> findByValue(String value);
}
