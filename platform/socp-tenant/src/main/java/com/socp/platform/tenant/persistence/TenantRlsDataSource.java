package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Applies the request tenant to every connection used by a PostgreSQL RLS
 * enabled application.
 *
 * <p>Hikari connections are pooled, so setting the value only once at pool
 * creation would be unsafe.  The wrapper sets it on checkout and immediately
 * before every statement factory call.  A missing request scope is represented
 * by a value that no tenant policy matches; explicit maintenance code must use
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
        setScope(delegate);
        InvocationHandler handler = new ConnectionHandler(delegate);
        return (Connection) Proxy.newProxyInstance(
                TenantRlsDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
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

        private ConnectionHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("createStatement") || name.equals("prepareStatement")
                    || name.equals("prepareCall")) {
                setScope(delegate);
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
