package com.socp.platform.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves integration-test images from the repository middleware catalog.
 *
 * <p>The catalog is deliberately read at test runtime so Testcontainers and
 * Compose cannot silently use different image tags. The path can be
 * overridden for IDEs and external test harnesses with
 * {@code SOCP_MIDDLEWARE_IMAGES_FILE} or the matching system property.</p>
 */
public final class MiddlewareImages {

    private static final Map<String, String> SERVICE_KEYS = Map.of(
            "postgres", "SOCP_POSTGRES_IMAGE",
            "kafka", "SOCP_KAFKA_IMAGE",
            "opensearch", "SOCP_OPENSEARCH_IMAGE",
            "opensearch-dashboards", "SOCP_OPENSEARCH_DASHBOARDS_IMAGE",
            "redis", "SOCP_REDIS_IMAGE",
            "clickhouse", "SOCP_CLICKHOUSE_IMAGE");

    private static final Map<String, String> FIXTURE_KEYS = Map.of(
            "proxy", "SOCP_PROXY_FIXTURE_IMAGE",
            "vector", "SOCP_VECTOR_IMAGE");

    private MiddlewareImages() {
    }

    public static String postgres() {
        return image("postgres");
    }

    public static String kafka() {
        return image("kafka");
    }

    public static String opensearch() {
        return image("opensearch");
    }

    public static String redis() {
        return image("redis");
    }

    public static String clickhouse() {
        return image("clickhouse");
    }

    public static String proxy() {
        return image("proxy");
    }

    public static String image(String service) {
        String key = SERVICE_KEYS.get(service);
        if (key == null) {
            key = FIXTURE_KEYS.get(service);
        }
        if (key == null) {
            throw new IllegalArgumentException("Unknown middleware service: " + service);
        }
        Map<String, String> catalog = loadCatalog();
        String image = catalog.get(key);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("Middleware image catalog is missing " + key);
        }
        return image;
    }

    private static Map<String, String> loadCatalog() {
        Path catalog = locateCatalog();
        try {
            Map<String, String> values = new LinkedHashMap<>();
            List<String> lines = Files.readAllLines(catalog, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index).trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new IllegalStateException("Invalid middleware catalog line " + (index + 1)
                            + " in " + catalog);
                }
                String key = line.substring(0, separator).trim();
                String value = stripQuotes(line.substring(separator + 1).trim());
                if (value.isEmpty() || values.putIfAbsent(key, value) != null) {
                    throw new IllegalStateException("Invalid or duplicate middleware catalog key " + key
                            + " in " + catalog);
                }
            }
            return values;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read middleware image catalog " + catalog, exception);
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && value.charAt(0) == value.charAt(value.length() - 1)
                && (value.charAt(0) == '\'' || value.charAt(0) == '"')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Path locateCatalog() {
        String configured = System.getProperty("socp.middleware.images.file");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("SOCP_MIDDLEWARE_IMAGES_FILE");
        }
        if (configured != null && !configured.isBlank()) {
            Path path = Paths.get(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
            throw new IllegalStateException("Configured middleware image catalog does not exist: " + path);
        }

        String multiModuleRoot = System.getProperty("maven.multiModuleProjectDirectory");
        Path fromMaven = findFrom(Paths.get(multiModuleRoot == null ? "." : multiModuleRoot));
        if (fromMaven != null) {
            return fromMaven;
        }
        Path fromWorkingDirectory = findFrom(Paths.get(System.getProperty("user.dir", ".")));
        if (fromWorkingDirectory != null) {
            return fromWorkingDirectory;
        }
        throw new IllegalStateException("Cannot locate infra/middleware-images.env; set "
                + "SOCP_MIDDLEWARE_IMAGES_FILE or socp.middleware.images.file");
    }

    private static Path findFrom(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("infra/middleware-images.env");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }
}
