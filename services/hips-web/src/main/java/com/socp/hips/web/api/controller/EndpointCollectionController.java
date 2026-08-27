package com.socp.hips.web.api.controller;

import com.socp.hips.web.api.request.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.hips.web.persistence.store.EndpointEventStore;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

/** Endpoint event ingress hosted by the HIPS domain deployment. */
@RestController
@RequestMapping("/api/v1")
public class EndpointCollectionController {

    private static final Logger log = LoggerFactory.getLogger(EndpointCollectionController.class);

    private final EndpointEventStore events;
    private final SocpHttpClient http;
    private final ObjectMapper objectMapper;

    public EndpointCollectionController(EndpointEventStore events, SocpHttpClient http, ObjectMapper objectMapper) {
        this.events = events;
        this.http = http;
        this.objectMapper = objectMapper;
    }

    @com.socp.platform.auth.security.RequireIngestIdentity
    @PostMapping("/events")
    public Map<String, Object> report(@Valid @RequestBody EndpointEventRequest input) {
        Map<String, Object> event = events.add(input.asMap());
        ServiceCall forward = http.post(SocpService.SEARCH, "/api/v1/ingest", serialize(event),
                SocpHttpClient.NDJSON, 5000);
        if (!forward.ok()) {
            log.warn("Endpoint event forwarding failed id={} reason={}",
                    event.get("eventId"), forward.failureReason());
        }
        return Map.of(
                "accepted", true,
                "eventId", event.get("eventId"),
                "total", events.list().size(),
                "forwarded", forward.ok());
    }

    @GetMapping({"/events", "/simulated"})
    public List<Map<String, Object>> events() {
        return events.list();
    }

    private String serialize(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize endpoint event", ex);
        }
    }
}
