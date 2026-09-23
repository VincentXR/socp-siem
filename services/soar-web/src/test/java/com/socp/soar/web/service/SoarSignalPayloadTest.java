package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SoarSignalPayloadTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void boundsManualInputBytesAndAcceptsLegacyEmptyKeys() throws Exception {
        var parsed = SoarSignalPayload.parse(mapper, "MANUAL_TASK", "", "{\"input\":{\"ticket\":\"123\"}}");
        assertEquals("", parsed.key());
        assertEquals(Map.of("ticket", "123"), mapper.readValue(parsed.inputJson(), Map.class));
        String large = mapper.writeValueAsString(Map.of("input", Map.of("text", "汉".repeat(SoarDefinitionValidator.MAX_BYTES / 3))));
        assertThrows(SoarSignalPayload.Invalid.class, () -> SoarSignalPayload.parse(mapper, "MANUAL_TASK", "", large));
        assertThrows(SoarSignalPayload.Invalid.class, () -> SoarSignalPayload.parse(mapper, "APPROVAL", "", " "));
        assertThrows(SoarSignalPayload.Invalid.class, () -> SoarSignalPayload.parse(mapper, "APPROVAL", "", null));
    }

    @Test void resolutionRequiresExecutableEnumAndReviewEvidence() {
        var parsed = SoarSignalPayload.parse(mapper, "UNKNOWN_RESOLUTION", "node",
                "{\"nodeId\":\"node\",\"resolution\":\"CONFIRMED_NOT_EXECUTED\",\"evidence\":\"receipt\",\"reason\":\"reviewed\"}");
        assertEquals("CONFIRMED_NOT_EXECUTED", parsed.resolution());
        assertThrows(SoarSignalPayload.Invalid.class, () -> SoarSignalPayload.parse(mapper, "UNKNOWN_RESOLUTION", "node",
                "{\"nodeId\":\"node\",\"resolution\":\"CONFIRMED_SUCCEEDED\"}"));
    }

    @Test void invalidEnqueueFailsBeforeRepositoryAccessAndRollsBackApprovalTransactions() throws Exception {
        var signals = org.mockito.Mockito.mock(com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository.class);
        var service = new SoarService(null, null, null, null, null, null, null, null, mapper,
                null, null, null, signals, null, null);
        var run = new com.socp.soar.web.persistence.entity.SoarRunEntity();
        run.setId("run"); run.setTenantId("tenant-a");
        var failure = assertThrows(com.socp.platform.error.exception.ApiException.class,
                () -> service.enqueueSignal(run, "APPROVAL", Map.of("approve", "true")));
        assertEquals(400, failure.getCode());
        assertTrue(failure.getMessage().startsWith("SOAR_INVALID_SIGNAL:"));
        org.mockito.Mockito.verifyNoInteractions(signals);
        var attributes = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        var method = SoarService.class.getMethod("decideApproval", String.class, boolean.class, String.class);
        var transaction = attributes.getTransactionAttribute(method, SoarService.class);
        assertNotNull(transaction);
        assertTrue(transaction.rollbackOn(failure));
        assertFalse(transaction.rollbackOn(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "approval expired")));
    }
}
