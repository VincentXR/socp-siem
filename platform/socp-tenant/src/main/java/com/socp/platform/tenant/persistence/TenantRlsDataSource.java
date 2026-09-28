package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies the request tenant to every connection used by a PostgreSQL RLS
 * enabled application.
 *
 * <p>Hikari connections are pooled, so setting the value only once at pool
 * creation would be unsafe. The wrapper sets it on checkout and reasserts it
 * before statement creation/execution whenever the effective scope changed or
 * a transaction boundary made the server-side value uncertain. The execution
 * hook matters for transaction-managed connections: Spring may acquire a
 * connection before an Activity installs its tenant scope, and some JDBC
 * drivers/Hibernate paths retain a prepared statement across that scope
 * change. Repeated statements within one stable scope do not issue redundant
 * {@code set_config} calls. A missing request scope is represented by a value
 * that no tenant policy matches; explicit maintenance code must use
 * {@link TenantContext#runAsSystem(Runnable)}.</p>
 */
public final class TenantRlsDataSource extends DelegatingDataSource {

    static final String SETTING = "socp.tenant_id";
    static final String NO_SCOPE = "__no_tenant_scope__";
    static final String SYSTEM_SCOPE = "*";

    public TenantRlsDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private static Connection wrap(Connection delegate) throws SQLException {
        try {
            setScope(delegate);
            InvocationHandler handler = new ConnectionHandler(delegate, scopeValue());
            return (Connection) Proxy.newProxyInstance(
                    TenantRlsDataSource.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                delegate.close();
            } catch (SQLException | RuntimeException | Error closeFailure) {
                if (closeFailure != failure) failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void setScope(Connection connection) throws SQLException {
        String value = TenantContext.isSystemScope()
                ? SYSTEM_SCOPE
                : TenantContext.get() == null ? NO_SCOPE : TenantContext.get();
        try (PreparedStatement statement = connection.prepareStatement(
                "select set_config(?, ?, false)")) {
            statement.setString(1, SETTING);
            statement.setString(2, value);
            statement.execute();
        }
    }

    static String scopeValue() {
        return TenantContext.isSystemScope()
                ? SYSTEM_SCOPE
                : TenantContext.get() == null ? NO_SCOPE : TenantContext.get();
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection delegate;
        private String appliedScope;

        private ConnectionHandler(Connection delegate, String appliedScope) {
            this.delegate = delegate;
            this.appliedScope = appliedScope;
        }

        private synchronized void ensureScope() throws SQLException {
            String required = scopeValue();
            if (required.equals(appliedScope)) return;
            setScope(delegate);
            appliedScope = required;
        }

        private synchronized void invalidateScope() {
            appliedScope = null;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("createStatement") || name.equals("prepareStatement")
                    || name.equals("prepareCall")) {
                ensureScope();
            }
            try {
                Object result = method.invoke(delegate, args);
                if (name.equals("createStatement") || name.equals("prepareStatement")
                        || name.equals("prepareCall")) {
                    return wrapStatement(this, result);
                }
                if (name.equals("commit") || name.equals("rollback") || name.equals("setAutoCommit")) {
                    // PostgreSQL can restore a previous session setting when a
                    // transaction is rolled back. Reassert before the next
                    // statement instead of trusting stale wrapper state.
                    invalidateScope();
                }
                return result;
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private static Object wrapStatement(ConnectionHandler connection, Object statement) {
        if (!(statement instanceof Statement jdbcStatement)) return statement;
        Class<?> contract = jdbcStatement instanceof CallableStatement
                ? CallableStatement.class
                : jdbcStatement instanceof PreparedStatement
                ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(
                TenantRlsDataSource.class.getClassLoader(),
                new Class<?>[]{contract}, new StatementHandler(connection, jdbcStatement));
    }

    private static final class StatementHandler implements InvocationHandler {
        private final ConnectionHandler connection;
        private final Statement delegate;

        private StatementHandler(ConnectionHandler connection, Statement delegate) {
            this.connection = connection;
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.startsWith("execute") || name.equals("addBatch")) {
                connection.ensureScope();
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
