package com.socp.detect.model.persistence;

import com.zaxxer.hikari.HikariDataSource;
import com.socp.detect.model.persistence.entity.AnalyzedEntity;
import com.socp.detect.web.config.DetectRuntimeRole;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Keeps the retired detect-model schema and transaction boundary independent
 * while hosting its secondary-analysis capability in the Detection worker.
 */
@Configuration(proxyBeanMethods = false)
@DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
@EnableJpaRepositories(
        basePackages = "com.socp.detect.model.persistence.repository",
        entityManagerFactoryRef = "secondaryAnalysisEntityManagerFactory",
        transactionManagerRef = "secondaryAnalysisTransactionManager")
public class SecondaryAnalysisPersistenceConfiguration {

    @Bean(name = "secondaryAnalysisDataSource", destroyMethod = "close")
    DataSource secondaryAnalysisDataSource(
            @Value("${socp.detect.model.datasource.url}") String url,
            @Value("${socp.detect.model.datasource.driver-class-name}") String driverClassName,
            @Value("${socp.detect.model.datasource.username}") String username,
            @Value("${socp.detect.model.datasource.password}") String password,
            @Value("${socp.detect.model.datasource.hikari.maximum-pool-size:2}") int maximumPoolSize,
            @Value("${socp.detect.model.datasource.hikari.minimum-idle:0}") int minimumIdle) {
        DataSource dataSource = DataSourceBuilder.create()
                .url(url)
                .driverClassName(driverClassName)
                .username(username)
                .password(password)
                .build();
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.setMaximumPoolSize(Math.max(1, maximumPoolSize));
            hikari.setMinimumIdle(Math.max(0, Math.min(minimumIdle, hikari.getMaximumPoolSize())));
            hikari.setPoolName("secondary-analysis");
        }
        return dataSource;
    }

    @Bean(name = "secondaryAnalysisFlyway", initMethod = "migrate")
    Flyway secondaryAnalysisFlyway(
            @Value("${socp.detect.model.flyway.url}") String url,
            @Value("${socp.detect.model.flyway.user}") String user,
            @Value("${socp.detect.model.flyway.password}") String password) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/secondary-analysis")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    @Bean(name = "secondaryAnalysisEntityManagerFactory")
    @DependsOn("secondaryAnalysisFlyway")
    LocalContainerEntityManagerFactoryBean secondaryAnalysisEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("secondaryAnalysisDataSource") DataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages(AnalyzedEntity.class)
                .persistenceUnit("secondaryAnalysis")
                .build();
    }

    @Bean(name = "secondaryAnalysisTransactionManager")
    PlatformTransactionManager secondaryAnalysisTransactionManager(
            @Qualifier("secondaryAnalysisEntityManagerFactory")
            LocalContainerEntityManagerFactoryBean entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory.getObject());
    }

}
