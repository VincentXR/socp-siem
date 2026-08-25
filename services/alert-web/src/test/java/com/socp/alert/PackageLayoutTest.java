package com.socp.alert;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the alert module's package layout intentional as it grows. */
class PackageLayoutTest {

    @Test
    void productionClassesUseAFeatureLayerPackage() throws IOException {
        Path root = Path.of("src/main/java/com/socp/alert");
        List<String> allowed = List.of("api", "config", "domain", "repository", "service");

        try (Stream<Path> files = Files.walk(root)) {
            List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(javaFiles).isNotEmpty();
            for (Path file : javaFiles) {
                Path relative = root.relativize(file);
                if (relative.getNameCount() == 1) {
                    assertThat(relative.getFileName().toString()).isEqualTo("AlertWebApplication.java");
                    continue;
                }
                String layer = relative.getName(0).toString();
                assertThat(layer).isIn(allowed);
                String packageName = Files.readAllLines(file).stream()
                        .filter(line -> line.startsWith("package "))
                        .findFirst()
                        .orElseThrow();
                assertThat(packageName).isEqualTo("package com.socp.alert." + layer + ";");
            }
        }
    }
}
