package com.socp.hips.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.hips.web.persistence.store.EndpointEventStore;
import com.socp.hips.web.persistence.store.EndpointForwardingStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EndpointEventDelivery {
    private final EndpointEventStore events;
    private final EndpointForwardingStore outbox;
    private final EndpointForwardingPublisher publisher;
    private final ObjectMapper mapper;
    private final int maxEventBytes;

    public EndpointEventDelivery(EndpointEventStore events, EndpointForwardingStore outbox,
            EndpointForwardingPublisher publisher, ObjectMapper mapper,
            @Value("${socp.hips.forwarding.max-event-bytes:262144}") int maxEventBytes) {
        if (maxEventBytes < 1 || maxEventBytes > 16777216)
            throw new IllegalArgumentException("Endpoint event byte limit must be within 1..16777216");
        this.events = events; this.outbox = outbox; this.publisher = publisher; this.mapper = mapper;
        this.maxEventBytes = maxEventBytes;
    }

    /** History, heartbeat and publication intent either commit together or all roll back. */
    @Transactional
    public Map<String, Object> accept(Map<String, Object> input) {
        return accept(input, null, null);
    }

    /** A caller key only deduplicates within the authenticated producer and tenant. */
    @Transactional
    public Map<String, Object> accept(Map<String, Object> input, String producer, String idempotencyKey) {
        String tenant = TenantContext.require();
        EndpointForwardingStore.RequestIdentity request = requestIdentity(input, producer, idempotencyKey);
        outbox.lockAdmission();
        if (request != null) {
            var replay = outbox.findRequest(tenant, request);
            if (replay.isPresent()) {
                if (!request.fingerprint().equals(replay.get().fingerprint()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key already used for different endpoint content");
                return readReplay(replay.get(), tenant);
            }
        }
        Map<String, Object> event = events.add(input);
        try {
            String payload = mapper.writeValueAsString(event);
            if (payload.getBytes(StandardCharsets.UTF_8).length > maxEventBytes)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Endpoint event exceeds forwarding byte limit");
            outbox.enqueue((String) event.get("eventId"), (String) event.get("tenantId"),
                    payload, Instant.now(), request);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize endpoint event", ex);
        }
        return event;
    }

    private EndpointForwardingStore.RequestIdentity requestIdentity(Map<String, Object> input,
            String producer, String key) {
        if (key == null) return null;
        if (key.isEmpty() || key.length() > 256 || key.chars().anyMatch(c -> c < 33 || c > 126))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1..256 visible ASCII characters");
        if (producer == null || producer.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated producer identity is required for idempotency");
        try {
            String canonical = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(input);
            return new EndpointForwardingStore.RequestIdentity(hash(producer), hash(key), hash("v1\n" + canonical));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to fingerprint endpoint request", ex);
        }
    }

    private Map<String, Object> readReplay(EndpointForwardingStore.Replay replay, String tenant) {
        try {
            Map<String, Object> event = mapper.readerFor(new TypeReference<Map<String, Object>>() { })
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(replay.payload());
            if (event == null || !tenant.equals(event.get("tenantId")) || !replay.eventId().equals(event.get("eventId")))
                throw new IllegalStateException("Endpoint receipt payload identity is invalid");
            return event;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Endpoint receipt payload cannot be read", ex);
        }
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public boolean forward(Map<String, Object> event) {
        return publisher.forward((String) event.get("tenantId"), (String) event.get("eventId"));
    }

    public String status(Map<String, Object> event) {
        return outbox.status((String) event.get("tenantId"), (String) event.get("eventId"));
    }
}
