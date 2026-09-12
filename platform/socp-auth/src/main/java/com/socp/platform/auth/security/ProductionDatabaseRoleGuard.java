package com.socp.platform.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;

/**
 * Verifies that production PostgreSQL-backed services actually run with a
 * tenant-enforcing role. PostgreSQL superusers and BYPASSRLS roles ignore row
 * level security even when policies are enabled/forced, so allowing either
 * would make the RLS configuration a false safety boundary.
 */
@Component
@Profile("prod")
public final class ProductionDatabaseRoleGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionDatabaseRoleGuard.class);

    private final Environment environment;
    private final ObjectProvider<DataSource> dataSources;

    public ProductionDatabaseRoleGuard(Environment environment, ObjectProvider<DataSource> dataSources) {
        this.environment = environment;
        this.dataSources = dataSources;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:")) return;
        if (!Boolean.parseBoolean(environment.getProperty("socp.tenant.rls.enabled", "false"))) return;

        DataSource dataSource = dataSources.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException("Production PostgreSQL datasource is configured but no DataSource bean is available");
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT current_user, r.rolsuper, r.rolbypassrls
                       FROM pg_roles r
                      WHERE r.rolname = current_user
                     """);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("Unable to resolve current PostgreSQL runtime role");
            }
            String role = result.getString(1);
            boolean superuser = result.getBoolean(2);
            boolean bypassRls = result.getBoolean(3);
            if (superuser || bypassRls) {
                throw new IllegalStateException("Production PostgreSQL runtime role '" + role
                        + "' bypasses RLS (rolsuper=" + superuser + ", rolbypassrls=" + bypassRls
                        + "). Use a NOSUPERUSER NOBYPASSRLS application role.");
            }
            log.info("Production PostgreSQL runtime role verified for RLS: {}", role);
        }
    }
}
