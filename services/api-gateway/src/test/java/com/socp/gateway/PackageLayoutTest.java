package com.socp.gateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps gateway entry points, filters and OIDC support code in intentional layers. */
class PackageLayoutTest {

    @Test
    void productionClassesUseFeatureLayerPackages() throws IOException {
        Path root = Path.of("src/main/java/com/socp/gateway");
        List<String> allowed = List.of("api", "filter", "oidc", "security");

        try (Stream<Path> files = Files.walk(root)) {
            List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(javaFiles).isNotEmpty();
            for (Path file : javaFiles) {
                Path relative = root.relativize(file);
                if (relative.getNameCount() == 1) {
                    assertThat(relative.getFileName().toString()).isEqualTo("ApiGatewayApplication.java");
                    continue;
                }
                String layer = relative.getName(0).toString();
                assertThat(layer).isIn(allowed);
                String packageName = Files.readAllLines(file).stream()
                        .filter(line -> line.startsWith("package "))
                        .findFirst()
                        .orElseThrow();
                assertThat(packageName).isEqualTo("package com.socp.gateway." + layer + ";");
            }
        }
    }
}
