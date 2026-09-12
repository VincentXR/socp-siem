package com.socp.platform.error.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PageResponse 1-based 分页契约与序列化字段名测试。 */
class PageResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesCanonicalFieldNames() throws Exception {
        var page = PageResponse.of(List.of("a", "b"), 11, 2, 10);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(page));

        assertEquals(2, json.get("items").size());
        assertEquals(11, json.get("total").asLong());
        assertEquals(2, json.get("page").asInt());
        assertEquals(10, json.get("size").asInt());
        assertEquals(2, json.get("totalPages").asInt());
    }

    @Test
    void computesTotalPagesByCeiling() {
        assertEquals(0, PageResponse.of(List.of(), 0, 1, 20).totalPages());
        assertEquals(1, PageResponse.of(List.of("a"), 1, 1, 20).totalPages());
        assertEquals(2, PageResponse.of(List.of("a"), 21, 1, 20).totalPages());
    }

    @Test
    void omitsTotalPagesWhenSizeInvalid() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(PageResponse.of(List.of(), 0, 1, 0)));
        assertFalse(json.has("totalPages"));
    }

    @Test
    void rejectsZeroAndNegativePage() {
        assertThrows(IllegalArgumentException.class, () -> PageResponse.of(List.of(), 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> PageResponse.of(List.of(), 0, -1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new PageResponse<>(List.of(), 0, 0, 10, null));
    }

    @Test
    void rejectsNegativeSizeAndTotal() {
        assertThrows(IllegalArgumentException.class, () -> PageResponse.of(List.of(), 0, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> PageResponse.of(List.of(), -1, 1, 10));
    }

    @Test
    void defensiveCopyKeepsItemsImmutable() {
        var source = new java.util.ArrayList<>(List.of("a"));
        var page = PageResponse.of(source, 1, 1, 10);
        source.clear();
        assertEquals(1, page.items().size());
        assertTrue(page.items() instanceof List);
        assertThrows(UnsupportedOperationException.class, () -> page.items().add("b"));
    }

    @Test
    void nullItemsBecomesEmptyList() {
        var page = PageResponse.of(null, 0, 1, 10);
        assertTrue(page.items().isEmpty());
        assertEquals(0, page.totalPages());
    }
}
