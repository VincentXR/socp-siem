package com.socp.notify.web.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** All channel mutations share a durable tenant lock, including first creation. */
@Component
public class ChannelCoordinator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean postgres;

    public ChannelCoordinator(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(3);
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(3);
        String product = this.jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        if (!"PostgreSQL".equals(product) && !"H2".equals(product)) throw new IllegalStateException("Unsupported notification database");
        postgres = "PostgreSQL".equals(product);
    }

    public <T> T mutate(Supplier<T> operation) {
        String tenant = TenantContext.require();
        return transactions.execute(tx -> {
            if (postgres) {
                jdbc.update("insert into t_notification_channel_namespace (tenant_id) values (?) on conflict (tenant_id) do nothing", tenant);
            } else {
                try {
                    jdbc.update("""
                            merge into t_notification_channel_namespace as target
                            using (values (cast(? as varchar(64)))) as incoming(tenant_id)
                            on target.tenant_id = incoming.tenant_id
                            when not matched then insert (tenant_id) values (incoming.tenant_id)
                            """, tenant);
                } catch (DuplicateKeyException concurrentInsert) {
                    // H2 statement rollback; locked read below must find the committed winner.
                }
            }
            jdbc.queryForObject("select tenant_id from t_notification_channel_namespace where tenant_id = ? for update", String.class, tenant);
            return operation.get();
        });
    }
}
