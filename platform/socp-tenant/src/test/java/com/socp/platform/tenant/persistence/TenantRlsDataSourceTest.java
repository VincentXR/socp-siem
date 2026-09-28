package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantRlsDataSourceTest {

    @Test
    void returnsBorrowedConnectionWhenScopeInitializationFails() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        SQLException failure = new SQLException("scope unavailable");
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenThrow(failure);

        assertSame(failure, assertThrows(SQLException.class,
                () -> new TenantRlsDataSource(delegate).getConnection()));
        verify(connection).close();
    }

    @Test
    void credentialedFailurePreservesInitializationAndCloseErrors() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        SQLException failure = new SQLException("scope unavailable");
        SQLException closeFailure = new SQLException("return failed");
        when(delegate.getConnection("user", "password")).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenThrow(failure);
        doThrow(closeFailure).when(connection).close();

        assertSame(failure, assertThrows(SQLException.class,
                () -> new TenantRlsDataSource(delegate).getConnection("user", "password")));
        assertEquals(1, failure.getSuppressed().length);
        assertSame(closeFailure, failure.getSuppressed()[0]);
        verify(connection).close();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void stableScopeIsAppliedOnceAcrossRepeatedStatementCreationAndExecution() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement scopeStatement = mock(PreparedStatement.class);
        PreparedStatement applicationStatement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select set_config(?, ?, false)")).thenReturn(scopeStatement);
        when(connection.prepareStatement("select 1")).thenReturn(applicationStatement);

        TenantContext.set("tenant-a");
        try (Connection wrapped = new TenantRlsDataSource(delegate).getConnection()) {
            PreparedStatement prepared = wrapped.prepareStatement("select 1");
            prepared.execute();
            prepared.executeQuery();
            wrapped.prepareStatement("select 1");
        }

        verify(scopeStatement, times(1)).setString(2, "tenant-a");
        verify(scopeStatement, times(1)).execute();
    }

    @Test
    void wrapsCredentialedCheckoutAndRefreshesScopeForAllStatementFactories() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement scopeStatement = mock(PreparedStatement.class);
        Statement plainStatement = mock(Statement.class);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        CallableStatement callableStatement = mock(CallableStatement.class);
        when(delegate.getConnection("user", "password")).thenReturn(connection);
        when(connection.prepareStatement("select set_config(?, ?, false)")).thenReturn(scopeStatement);
        when(connection.createStatement()).thenReturn(plainStatement);
        when(connection.prepareStatement("select 1")).thenReturn(preparedStatement);
        when(connection.prepareCall("select 1")).thenReturn(callableStatement);

        TenantContext.set("tenant-a");
        try (Connection wrapped = new TenantRlsDataSource(delegate).getConnection("user", "password")) {
            wrapped.createStatement();
            wrapped.prepareStatement("select 1");
            wrapped.prepareCall("select 1");
        }

        verify(delegate).getConnection("user", "password");
        verify(scopeStatement, times(1)).setString(2, "tenant-a");
        verify(scopeStatement, times(1)).execute();
    }

    @Test
    void unwrapsDelegateExceptionsFromConnectionProxy() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement scopeStatement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(scopeStatement);
        doThrow(new SQLException("closed")).when(connection).close();

        Connection wrapped = new TenantRlsDataSource(delegate).getConnection();
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, wrapped::close);
    }

    @Test
    void refreshesScopeBeforeExecutingAStatementPreparedBeforeTenantSwitch() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement scopeStatement = mock(PreparedStatement.class);
        PreparedStatement applicationStatement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select set_config(?, ?, false)")).thenReturn(scopeStatement);
        when(connection.prepareStatement("select 1")).thenReturn(applicationStatement);

        TenantContext.set("tenant-a");
        Connection wrapped = new TenantRlsDataSource(delegate).getConnection();
        PreparedStatement prepared = wrapped.prepareStatement("select 1");
        TenantContext.set("tenant-b");
        prepared.execute();

        verify(scopeStatement, times(1)).setString(2, "tenant-a");
        verify(scopeStatement, times(1)).setString(2, "tenant-b");
        wrapped.close();
    }

    @Test
    void rollbackInvalidatesCachedScopeAndNextStatementReassertsIt() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement scopeStatement = mock(PreparedStatement.class);
        Statement applicationStatement = mock(Statement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select set_config(?, ?, false)")).thenReturn(scopeStatement);
        when(connection.createStatement()).thenReturn(applicationStatement);

        TenantContext.set("tenant-a");
        Connection wrapped = new TenantRlsDataSource(delegate).getConnection();
        wrapped.rollback();
        wrapped.createStatement().execute("select 1");

        verify(scopeStatement, times(2)).setString(2, "tenant-a");
        verify(scopeStatement, times(2)).execute();
        wrapped.close();
    }

    @Test
    void preparedBatchDetectsScopeChangeWithoutRepeatingStableScopeSql() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement scopeStatement = mock(PreparedStatement.class);
        PreparedStatement applicationStatement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select set_config(?, ?, false)")).thenReturn(scopeStatement);
        when(connection.prepareStatement("insert into t values (?)")).thenReturn(applicationStatement);

        TenantContext.set("tenant-a");
        Connection wrapped = new TenantRlsDataSource(delegate).getConnection();
        PreparedStatement prepared = wrapped.prepareStatement("insert into t values (?)");
        TenantContext.set("tenant-b");
        prepared.addBatch();
        prepared.addBatch();

        verify(scopeStatement, times(1)).setString(2, "tenant-a");
        verify(scopeStatement, times(1)).setString(2, "tenant-b");
        wrapped.close();
    }

    @Test
    void usesSystemMarkerOnlyInsideExplicitSystemScope() {
        assertEquals(TenantRlsDataSource.NO_SCOPE, TenantRlsDataSource.scopeValue());
        TenantContext.runAsSystem(() -> assertEquals(TenantRlsDataSource.SYSTEM_SCOPE,
                TenantRlsDataSource.scopeValue()));
    }
}
