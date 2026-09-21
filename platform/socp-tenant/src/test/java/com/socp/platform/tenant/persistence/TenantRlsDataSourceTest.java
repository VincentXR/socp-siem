package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
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
    void setsTenantOnCheckoutAndBeforeStatements() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        TenantContext.set("tenant-a");
        try (Connection ignored = new TenantRlsDataSource(delegate).getConnection()) {
            ignored.prepareStatement("select 1");
        }

        verify(statement, org.mockito.Mockito.atLeast(2)).setString(2, "tenant-a");
        verify(statement, org.mockito.Mockito.atLeast(2)).execute();
    }

    @Test
    void wrapsCredentialedCheckoutAndRefreshesScopeForAllStatementFactories() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection("user", "password")).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        TenantContext.set("tenant-a");
        try (Connection wrapped = new TenantRlsDataSource(delegate).getConnection("user", "password")) {
            wrapped.createStatement();
            wrapped.prepareStatement("select 1");
            wrapped.prepareCall("select 1");
        }

        verify(delegate).getConnection("user", "password");
        verify(statement, org.mockito.Mockito.atLeast(4)).setString(2, "tenant-a");
        verify(statement, org.mockito.Mockito.atLeast(4)).execute();
    }

    @Test
    void unwrapsDelegateExceptionsFromConnectionProxy() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        doThrow(new SQLException("closed")).when(connection).close();

        Connection wrapped = new TenantRlsDataSource(delegate).getConnection();
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, wrapped::close);
    }

    @Test
    void refreshesScopeBeforeExecutingAStatementPreparedBeforeTenantSwitch() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        TenantContext.set("tenant-a");
        Connection wrapped = new TenantRlsDataSource(delegate).getConnection();
        PreparedStatement prepared = wrapped.prepareStatement("select 1");
        TenantContext.set("tenant-b");
        prepared.execute();

        verify(statement, org.mockito.Mockito.atLeastOnce()).setString(2, "tenant-b");
        wrapped.close();
    }

    @Test
    void usesSystemMarkerOnlyInsideExplicitSystemScope() {
        assertEquals(TenantRlsDataSource.NO_SCOPE, TenantRlsDataSource.scopeValue());
        TenantContext.runAsSystem(() -> assertEquals(TenantRlsDataSource.SYSTEM_SCOPE,
                TenantRlsDataSource.scopeValue()));
    }
}
