package com.socp.platform.tenant.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Opt-in DataSource decoration for PostgreSQL row-level security. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = "socp.tenant.rls.enabled", havingValue = "true")
public class TenantRlsDataSourcePostProcessor
        implements org.springframework.beans.factory.config.BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(TenantRlsDataSourcePostProcessor.class);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(dataSource instanceof TenantRlsDataSource)) {
            log.info("PostgreSQL tenant RLS connection context enabled for DataSource bean={}", beanName);
            return new TenantRlsDataSource(dataSource);
        }
        return bean;
    }
}
