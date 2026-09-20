package com.socp.detect.web.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertStreamHubTest {

    @Test
    void broadcastsOnlyWithinTheOwningTenantAndSupportsDefaultSubscribers() {
        AlertStreamHub hub = new AlertStreamHub();
        PrintWriter tenantA = mock(PrintWriter.class);
        PrintWriter tenantB = mock(PrintWriter.class);
        PrintWriter defaultTenant = mock(PrintWriter.class);
        Alert alert = alert("alert-stream-1");

        hub.add("tenant-a", tenantA);
        hub.add("tenant-b", tenantB);
        hub.add(defaultTenant);
        hub.broadcast("tenant-a", alert);

        verify(tenantA).write(contains("event: alert"));
        verify(tenantA).write(contains("alert-stream-1"));
        verify(tenantA).flush();
        verify(tenantA).checkError();
        verify(tenantB, never()).write(anyString());
        verify(defaultTenant, never()).write(anyString());

        hub.broadcast(alert);
        verify(defaultTenant).write(contains("alert-stream-1"));
        verify(defaultTenant).flush();
        verify(defaultTenant).checkError();

        hub.remove(tenantA);
        org.junit.jupiter.api.Assertions.assertEquals(2, hub.subscriberCount());
    }

    @Test
    void removesAWriterWhenTheContainerLatchesAnOutputError() {
        AlertStreamHub hub = new AlertStreamHub();
        PrintWriter failing = mock(PrintWriter.class);
        PrintWriter healthy = mock(PrintWriter.class);
        when(failing.checkError()).thenReturn(true);
        hub.add("tenant-a", failing);
        hub.add("tenant-a", healthy);

        hub.broadcast("tenant-a", alert("alert-stream-2"));

        verify(failing).write(contains("alert-stream-2"));
        verify(failing).flush();
        verify(failing).checkError();
        verify(healthy).write(contains("alert-stream-2"));
        verify(healthy).flush();
        verify(healthy).checkError();
        org.junit.jupiter.api.Assertions.assertEquals(1, hub.subscriberCount());
    }

    private static Alert alert(String id) {
        return new Alert(id, Instant.parse("2026-08-30T00:00:00Z"),
                "RULE-1", "Suspicious login", Severity.HIGH,
                "failed login", "user-1", List.of());
    }
}
