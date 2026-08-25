package com.socp.search.config.infrastructure.serialization;

import com.socp.search.config.domain.SearchEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;

/** 检索事件 JSON 序列化（SearchEvent 含 Instant timestamp，必须注册 JavaTimeModule）。 */
public final class SearchEventJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static String toJson(List<SearchEvent> events) {
        try {
            return MAPPER.writeValueAsString(events);
        } catch (Exception e) {
            return "[]";
        }
    }

    private SearchEventJson() {
    }
}
