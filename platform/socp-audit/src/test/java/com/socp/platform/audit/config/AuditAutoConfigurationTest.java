package com.socp.platform.audit.config;

import com.socp.platform.audit.sink.AuditKafkaRuntime;
import com.socp.platform.audit.sink.JdbcAuditOutboxSink;
import com.socp.platform.audit.sink.KafkaAuditSink;
import com.socp.platform.audit.spi.AuditSink;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAutoConfigurationTest {

    @Test
    void databaseBackedServicesSelectTheTransactionalOutbox() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuditAutoConfiguration.class, DatabaseBeans.class)
                .withPropertyValues("socp.audit.sink=kafka")
                .run(context -> {
                    assertThat(context).hasSingleBean(AuditSink.class);
                    assertThat(context.getBean(AuditSink.class)).isInstanceOf(JdbcAuditOutboxSink.class);
                    assertThat(context).hasSingleBean(AuditKafkaRuntime.class);
                    assertThat(context.getBean(AuditKafkaRuntime.class).durableOutboxEnabled()).isTrue();
                    assertThat(context).doesNotHaveBean(KafkaAuditSink.class);
                });
    }

    @Test
    void statelessServicesRetainDirectKafkaDelivery() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuditAutoConfiguration.class)
                .withPropertyValues("socp.audit.sink=kafka")
                .run(context -> {
                    assertThat(context).hasSingleBean(AuditSink.class);
                    assertThat(context.getBean(AuditSink.class)).isInstanceOf(KafkaAuditSink.class);
                    assertThat(context.getBean(AuditKafkaRuntime.class).durableOutboxEnabled()).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class DatabaseBeans {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:audit-config-" + System.nanoTime()
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            return source;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
