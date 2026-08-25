package com.socp.asset.collect.collector;

import com.socp.asset.collect.config.AssetCollectProperties;
import com.socp.asset.collect.store.AssetCollectionStore;
import com.socp.platform.client.AssetClient;
import com.socp.platform.client.ServiceCall;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AssetScannerTest {

    @Test
    void disabledSimulationDoesNotCreateOrForwardRecords() {
        AssetClient client = mock(AssetClient.class);
        AssetCollectionStore store = mock(AssetCollectionStore.class);
        AssetCollectProperties properties = new AssetCollectProperties();
        properties.setSimulationEnabled(false);
        Environment environment = mock(Environment.class);

        new AssetScanner(client, store, properties, environment).scan();

        verifyNoInteractions(client, store);
    }

    @Test
    void simulationPersistsEveryGeneratedAssetBeforeForwarding() {
        AssetClient client = mock(AssetClient.class);
        AssetCollectionStore store = mock(AssetCollectionStore.class);
        AssetCollectProperties properties = new AssetCollectProperties();
        properties.setSimulationEnabled(true);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(store.append(eq("default"), any())).thenAnswer(invocation -> {
            Map<String, Object> record = new LinkedHashMap<>(invocation.getArgument(1));
            record.put("id", "asset-" + record.get("name"));
            return record;
        });
        when(client.collect(anyString())).thenReturn(
                new ServiceCall(null, "/asset", true, 200, "", null, 1, false, 1));

        AssetScanner scanner = new AssetScanner(client, store, properties, environment);
        scanner.scan();

        verify(store, times(3)).append(eq("default"), argThat(asset ->
                "simulator".equals(asset.get("source"))));
        verify(client, times(3)).collect(anyString());
        assertThat(scanner.discovered()).isEmpty();
    }

    @Test
    void productionProfileDisablesSimulationEvenWhenFlagIsEnabled() {
        AssetClient client = mock(AssetClient.class);
        AssetCollectionStore store = mock(AssetCollectionStore.class);
        AssetCollectProperties properties = new AssetCollectProperties();
        properties.setSimulationEnabled(true);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        new AssetScanner(client, store, properties, environment).scan();

        verifyNoInteractions(client, store);
    }
}
