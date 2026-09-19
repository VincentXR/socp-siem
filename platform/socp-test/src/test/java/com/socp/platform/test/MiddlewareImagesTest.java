package com.socp.platform.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MiddlewareImagesTest {

    private static final String CATALOG_PROPERTY = "socp.middleware.images.file";

    @AfterEach
    void clearCatalogOverride() {
        System.clearProperty(CATALOG_PROPERTY);
    }

    @Test
    void resolvesServiceAndFixtureImagesFromConfiguredCatalog(@TempDir Path tempDir) throws IOException {
        Path catalog = writeCatalog(tempDir,
                "SOCP_POSTGRES_IMAGE='unit-postgres-image'\n"
                        + "SOCP_KAFKA_IMAGE=unit-kafka-image\n"
                        + "SOCP_OPENSEARCH_IMAGE=unit-opensearch-image\n"
                        + "SOCP_OPENSEARCH_DASHBOARDS_IMAGE=unit-dashboards-image\n"
                        + "SOCP_REDIS_IMAGE=unit-redis-image\n"
                        + "SOCP_CLICKHOUSE_IMAGE=unit-clickhouse-image\n"
                        + "SOCP_PROXY_FIXTURE_IMAGE=unit-proxy-image\n"
                        + "SOCP_VECTOR_IMAGE=unit-vector-image\n");
        System.setProperty(CATALOG_PROPERTY, catalog.toString());

        assertEquals("unit-postgres-image", MiddlewareImages.postgres());
        assertEquals("unit-kafka-image", MiddlewareImages.kafka());
        assertEquals("unit-opensearch-image", MiddlewareImages.opensearch());
        assertEquals("unit-redis-image", MiddlewareImages.redis());
        assertEquals("unit-clickhouse-image", MiddlewareImages.clickhouse());
        assertEquals("unit-proxy-image", MiddlewareImages.proxy());
        assertEquals("unit-dashboards-image", MiddlewareImages.image("opensearch-dashboards"));
        assertEquals("unit-vector-image", MiddlewareImages.image("vector"));
    }

    @Test
    void rejectsUnknownServiceAndInvalidCatalog(@TempDir Path tempDir) throws IOException {
        Path invalidCatalog = writeCatalog(tempDir,
                "SOCP_POSTGRES_IMAGE=unit-postgres-image\nSOCP_POSTGRES_IMAGE=duplicate\n");
        System.setProperty(CATALOG_PROPERTY, invalidCatalog.toString());

        assertThrows(IllegalArgumentException.class, () -> MiddlewareImages.image("unknown"));
        assertThrows(IllegalStateException.class, MiddlewareImages::postgres);

        System.setProperty(CATALOG_PROPERTY, tempDir.resolve("missing.env").toString());
        IllegalStateException missing = assertThrows(IllegalStateException.class, MiddlewareImages::postgres);
        assertTrue(missing.getMessage().contains("does not exist"));
    }

    private static Path writeCatalog(Path directory, String content) throws IOException {
        Path catalog = directory.resolve("middleware-images.env");
        Files.writeString(catalog, content);
        return catalog;
    }
}
