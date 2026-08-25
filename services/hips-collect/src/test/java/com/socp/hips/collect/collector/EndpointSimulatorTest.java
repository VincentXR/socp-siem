package com.socp.hips.collect.collector;

import com.socp.hips.collect.config.HipsCollectProperties;
import com.socp.hips.collect.store.HipsEventStore;
import com.socp.platform.client.HipsClient;
import com.socp.platform.client.ServiceCall;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EndpointSimulatorTest {

    @Test
    void disabledSimulationDoesNotCreateOrForwardEvents() {
        HipsClient client = mock(HipsClient.class);
        HipsEventStore store = mock(HipsEventStore.class);
        HipsCollectProperties properties = new HipsCollectProperties();
        Environment environment = mock(Environment.class);

        new EndpointSimulator(client, store, properties, environment).simulate();

        verifyNoInteractions(client, store);
    }

    @Test
    void simulationPersistsEventBeforeForwarding() {
        HipsClient client = mock(HipsClient.class);
        HipsEventStore store = mock(HipsEventStore.class);
        HipsCollectProperties properties = new HipsCollectProperties();
        properties.setSimulationEnabled(true);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(store.append(eq("default"), any())).thenAnswer(invocation -> {
            Map<String, Object> record = new LinkedHashMap<>(invocation.getArgument(1));
            record.put("id", "event-1");
            return record;
        });
        when(client.reportEvent(anyString())).thenReturn(
                new ServiceCall(null, "/events", true, 200, "", null, 1, false, 1));

        new EndpointSimulator(client, store, properties, environment).simulate();

        verify(store).append(eq("default"), argThat(event ->
                "simulator".equals(event.get("source"))));
        verify(client).reportEvent(anyString());
    }
}
