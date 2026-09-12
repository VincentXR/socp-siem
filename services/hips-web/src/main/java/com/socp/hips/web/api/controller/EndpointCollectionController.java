package com.socp.hips.web.api.controller;

import com.socp.hips.web.api.request.EndpointEventRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
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
    private final int maxListSize;

    public EndpointCollectionController(EndpointEventStore events, SocpHttpClient http, ObjectMapper objectMapper,
                                        @Value("${socp.web.list-max-size:500}") int maxListSize) {
        this.events = events;
        this.http = http;
        this.objectMapper = objectMapper;
        this.maxListSize = maxListSize;
    }

    @com.socp.platform.auth.security.RequireIngestIdentity
    @PostMapping("/events")
    public ApiResult<Map<String, Object>> report(@Valid @RequestBody EndpointEventRequest input) {
        Map<String, Object> event = events.add(input.asMap());
        ServiceCall forward = http.post(SocpService.SEARCH, "/api/v1/ingest", serialize(event),
                SocpHttpClient.NDJSON, 5000);
        if (!forward.ok()) {
            log.warn("Endpoint event forwarding failed id={} reason={}",
                    event.get("eventId"), forward.failureReason());
        }
        return ApiResult.ok(Map.of(
                "accepted", true,
                "eventId", event.get("eventId"),
                "total", events.list().size(),
                "forwarded", forward.ok()));
    }

    /** 已接收端点事件：租户级分页（page 从 1 起，size 上限 socp.web.list-max-size）。 */
    @GetMapping({"/events", "/simulated"})
    public ApiResult<PageResponse<Map<String, Object>>> events(@RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(defaultValue = "500") int size) {
        requireValidRange(page, size);
        List<Map<String, Object>> all = events.list();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return ApiResult.ok(PageResponse.of(all.subList(from, to), all.size(), page, size));
    }

    private void requireValidRange(int page, int size) {
        if (page < 1 || size < 0 || size > maxListSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数非法：page 从 1 起，size 上限 " + maxListSize);
        }
    }

    private String serialize(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize endpoint event", ex);
        }
    }
}
