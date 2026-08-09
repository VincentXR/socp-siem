package com.socp.search.config.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;

/** 检索事件 JSON 序列化（SearchEvent 含 Instant timestamp，必须注册 JavaTimeModule）。 */
final class SearchEventJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    static String toJson(List<SearchEvent> events) {
        try {
            return MAPPER.writeValueAsString(events);
        } catch (Exception e) {
            return "[]";
        }
    }

    private SearchEventJson() {
    }
}
