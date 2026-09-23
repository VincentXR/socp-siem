package com.socp.detect.web.persistence.store;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.util.Objects;
import java.util.function.Supplier;

/** Database coordination for catalogue installation and tenant rule mutations. */
@Component
public class RuleCatalogCoordinator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean postgres;

    public RuleCatalogCoordinator(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(5);
        this.transactions = new TransactionTemplate(transactionManager);
        // REQUIRED keeps rule state, revision, change outbox and this lock in
        // the owning service transaction. Standalone installs own a transaction.
        this.transactions.setTimeout(5);
        String product = this.jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        if (!"PostgreSQL".equals(product) && !"H2".equals(product)) {
            throw new IllegalStateException("Unsupported rule catalogue database: " + product);
        }
        this.postgres = "PostgreSQL".equals(product);
    }

    public <T> T withCatalog(String tenant, Pack pack, Runnable install, Supplier<T> operation) {
        if (tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("valid catalogue tenant is required");
        }
        return transactions.execute(status -> {
            ensureNamespace(tenant);
            Pack installed = jdbc.queryForObject("select pack_id, pack_version, pack_fingerprint "
                            + "from t_rule_catalog where tenant_id = ? for update",
                    (row, index) -> row.getString(3) == null ? null
                            : new Pack(row.getString(1), row.getString(2), row.getString(3)), tenant);
            if (!pack.equals(installed)) {
                if (installed != null && pack.id().equals(installed.id())) {
                    int comparison = compareVersions(installed.version(), pack.version());
                    if (comparison > 0) throw new IllegalStateException("Database rule content is newer than this runtime");
                    if (comparison == 0) throw new IllegalStateException("Rule content changed without a version increment");
                }
                install.run();
                jdbc.update("update t_rule_catalog set pack_id = ?, pack_version = ?, pack_fingerprint = ? "
                                + "where tenant_id = ?", pack.id(), pack.version(), pack.fingerprint(), tenant);
            }
            return operation.get();
        });
    }

    private void ensureNamespace(String tenant) {
        if (postgres) {
            jdbc.update("insert into t_rule_catalog (tenant_id) values (?) on conflict (tenant_id) do nothing", tenant);
        } else {
            try {
                jdbc.update("""
                        merge into t_rule_catalog as target
                        using (values (cast(? as varchar(64)))) as incoming(tenant_id)
                        on target.tenant_id = incoming.tenant_id
                        when not matched then insert (tenant_id) values (incoming.tenant_id)
                        """, tenant);
            } catch (DuplicateKeyException concurrentInsert) {
                // H2 may resolve a concurrent MERGE as a duplicate. Unlike PG,
                // it rolls back that statement only; the following locked read
                // must still find the committed winner. Never catch rule writes.
            }
        }
    }

    private static int compareVersions(String first, String second) {
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            BigInteger a = index < left.length ? new BigInteger(left[index]) : BigInteger.ZERO;
            BigInteger b = index < right.length ? new BigInteger(right[index]) : BigInteger.ZERO;
            int result = a.compareTo(b);
            if (result != 0) return result;
        }
        return 0;
    }

    public record Pack(String id, String version, String fingerprint) {
        public Pack {
            if (id == null || id.isBlank() || id.length() > 128 || version == null
                    || version.length() > 64 || !version.matches("[0-9]+(?:\\.[0-9]+)*")
                    || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("valid content pack identity, numeric version and fingerprint are required");
            }
        }
    }
}
