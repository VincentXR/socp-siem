package com.socp.hips.web.api.controller;

import com.socp.hips.web.api.request.EndpointEventRequest;
import com.socp.hips.web.persistence.store.EndpointEventStore;
import com.socp.hips.web.service.EndpointEventDelivery;
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
import org.springframework.data.domain.Page;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestHeader;
import com.socp.platform.auth.security.CollectorCredentialRegistry;
import com.socp.platform.auth.security.RequireService;

import java.util.Map;

/** Endpoint event ingress hosted by the HIPS domain deployment. */
@RestController
@RequestMapping("/api/v1")
public class EndpointCollectionController {

    private final EndpointEventStore events;
    private final EndpointEventDelivery delivery;
    private final int maxListSize;

    public EndpointCollectionController(EndpointEventStore events, EndpointEventDelivery delivery,
                                        @Value("${socp.web.list-max-size:500}") int maxListSize) {
        this.events = events;
        this.delivery = delivery;
        this.maxListSize = maxListSize;
    }

    @com.socp.platform.auth.security.RequireIngestIdentity(
            maxBodyBytes = "${socp.hips.ingest.max-body-bytes:262144}")
    @PostMapping("/events")
    public ApiResult<Map<String, Object>> report(@Valid @RequestBody EndpointEventRequest input,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String collector = (String) request.getAttribute(CollectorCredentialRegistry.COLLECTOR_ID_ATTRIBUTE);
        String service = (String) request.getAttribute(RequireService.SERVICE_ID_ATTRIBUTE);
        // A verified service proof is the effective identity when AuthInterceptor delegates a tenant.
        String producer = service != null ? "service:" + service : collector != null ? "collector:" + collector : null;
        Map<String, Object> event = delivery.accept(input.asMap(), producer, idempotencyKey);
        boolean acknowledged = delivery.forward(event);
        return ApiResult.ok(Map.of(
                "accepted", true,
                "eventId", event.get("eventId"),
                "total", events.count(),
                "forwarded", acknowledged,
                "deliveryStatus", delivery.status(event)));
    }

    /** 已接收端点事件：租户级分页（page 从 1 起，size 上限 socp.web.list-max-size）。 */
    @GetMapping({"/events", "/simulated"})
    public ApiResult<PageResponse<Map<String, Object>>> events(@RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(defaultValue = "500") int size) {
        requireValidRange(page, size);
        Page<Map<String, Object>> result = events.page(page, size);
        return ApiResult.ok(PageResponse.of(result.getContent(), result.getTotalElements(),
                result.getNumber() + 1, result.getSize(), result.getTotalPages()));
    }

    private void requireValidRange(int page, int size) {
        if (page < 1 || size < 1 || size > maxListSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数非法：page 从 1 起，size 上限 " + maxListSize);
        }
    }

}
