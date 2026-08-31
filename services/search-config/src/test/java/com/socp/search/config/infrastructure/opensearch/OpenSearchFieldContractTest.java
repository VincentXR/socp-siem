package com.socp.search.config.infrastructure.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.query.FieldCatalog;
import com.socp.search.config.query.FieldDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchFieldContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void productionTemplateContainsEveryRegisteredTypedQueryPath() throws Exception {
        JsonNode properties = MAPPER.readTree(OpenSearchIndexTemplate.payload())
                .path("template").path("mappings").path("properties");

        for (FieldDescriptor field : FieldCatalog.standard().descriptors()) {
            assertMappedType(properties, field.searchPath(), searchTypes(field), field.name());
            assertMappedType(properties, field.exactPath(), exactTypes(field), field.name());
        }
    }

    private static void assertMappedType(JsonNode properties, String path,
                                         Set<String> expectedTypes, String fieldName) {
        JsonNode mapping = resolve(properties, path);
        assertThat(mapping.isMissingNode())
                .as("registered field %s path %s must be explicitly mapped", fieldName, path)
                .isFalse();
        assertThat(mapping.path("type").asText())
                .as("registered field %s path %s type", fieldName, path)
                .isIn(expectedTypes);
    }

    private static JsonNode resolve(JsonNode properties, String path) {
        JsonNode current = properties;
        for (String segment : path.split("\\.")) {
            if (current.has(segment)) {
                current = current.path(segment);
            } else if (current.path("properties").has(segment)) {
                current = current.path("properties").path(segment);
            } else if (current.path("fields").has(segment)) {
                current = current.path("fields").path(segment);
            } else {
                return current.path("__missing__");
            }
        }
        return current;
    }

    private static Set<String> searchTypes(FieldDescriptor field) {
        return switch (field.type()) {
            case DATE -> Set.of("date");
            case IP -> Set.of("ip");
            case INTEGER -> Set.of("integer", "long");
            case TEXT -> Set.of("text");
            default -> Set.of("keyword");
        };
    }

    private static Set<String> exactTypes(FieldDescriptor field) {
        return field.type() == FieldDescriptor.Type.TEXT
                ? Set.of("keyword") : searchTypes(field);
    }
}
