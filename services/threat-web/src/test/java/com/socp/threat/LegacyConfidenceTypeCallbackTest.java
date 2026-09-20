package com.socp.threat;

import com.socp.threat.web.config.LegacyConfidenceTypeCallback;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyConfidenceTypeCallbackTest {
    @Test
    void convertsTheColumnBeforeRemovingItsOwnCompatibilityType() throws Exception {
        var context = context();
        var statement = context.getConnection().createStatement();
        var rows = mock(ResultSet.class);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getBoolean(1)).thenReturn(false, true);
        var callback = new LegacyConfidenceTypeCallback();
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);
        callback.handle(Event.AFTER_EACH_MIGRATE, context);
        var ordered = inOrder(statement);
        ordered.verify(statement).execute("CREATE DOMAIN \"double\" AS DOUBLE PRECISION");
        ordered.verify(statement).execute("COMMENT ON DOMAIN \"double\" IS 'socp-threat-v2-confidence-compatibility'");
        ordered.verify(statement).execute("ALTER TABLE t_ioc ALTER COLUMN confidence TYPE DOUBLE PRECISION");
        ordered.verify(statement).execute("DROP DOMAIN \"double\"");
    }

    @Test
    void preservesAnOperatorOwnedType() throws Exception {
        var context = context();
        var statement = context.getConnection().createStatement();
        var rows = mock(ResultSet.class);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.getBoolean(1)).thenReturn(true, false);
        var callback = new LegacyConfidenceTypeCallback();
        callback.handle(Event.BEFORE_EACH_MIGRATE, context);
        callback.handle(Event.AFTER_EACH_MIGRATE, context);
        verify(statement, never()).execute(anyString());
    }

    @Test
    void databaseFailureAbortsMigrationWithTheOriginalCause() throws Exception {
        var context = context();
        var failure = new SQLException("permission denied");
        when(context.getConnection().createStatement()).thenThrow(failure);
        var error = assertThrows(FlywayException.class, () ->
                new LegacyConfidenceTypeCallback().handle(Event.BEFORE_EACH_MIGRATE, context));
        assertSame(failure, error.getCause());
    }

    private static Context context() throws SQLException {
        var context = mock(Context.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(context.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        return context;
    }
}
