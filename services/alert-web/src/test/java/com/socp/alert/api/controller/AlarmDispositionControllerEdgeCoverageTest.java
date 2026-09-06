package com.socp.alert.api.controller;

import com.socp.alert.api.request.AlarmNoteRequest;
import com.socp.alert.api.request.AlarmTagRequest;
import com.socp.alert.service.AlarmDispositionService;
import com.socp.alert.service.AlarmService;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Remaining delegation branches of the alarm disposition endpoints: the note
 * endpoint (operator fallback + Idempotency-Key forwarding) and the tag
 * endpoint, including the request record shape.
 */
@ExtendWith(MockitoExtension.class)
class AlarmDispositionControllerEdgeCoverageTest {

    @Mock
    private AlarmService alarmService;
    @Mock
    private AlarmDispositionService disposition;

    private AlarmDispositionController controller;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        controller = new AlarmDispositionController(alarmService, disposition);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AlarmDispositionService.Disposition open() {
        return new AlarmDispositionService.Disposition("OPEN", null, List.of());
    }

    @Test
    void addNoteAppliesTheOperatorFallbackAndForwardsTheIdempotencyHeader() {
        given(disposition.addNote("a1", "operator", "enriched", "run-1:key-1")).willReturn(open());
        AlarmNoteRequest body = new AlarmNoteRequest(null, "enriched");

        AlarmDispositionService.Disposition result = controller.addNote("a1", body, "run-1:key-1");

        assertThat(result.status()).isEqualTo("OPEN");
        verify(alarmService).get("a1");
    }

    @Test
    void addNoteKeepsAProvidedAuthorAndPassesAMissingHeaderAsNull() {
        given(disposition.addNote("a1", "alice", "note", null)).willReturn(open());
        AlarmNoteRequest body = new AlarmNoteRequest("alice", "note");

        AlarmDispositionService.Disposition result = controller.addNote("a1", body, null);

        assertThat(result.status()).isEqualTo("OPEN");
        verify(alarmService).get("a1");
    }

    @Test
    void addTagDelegatesTheTagFromTheRequestBody() {
        AlarmTagRequest body = new AlarmTagRequest("prod");
        assertThat(body.tag()).isEqualTo("prod");
        given(disposition.addTag("a1", "prod")).willReturn(open());

        AlarmDispositionService.Disposition result = controller.addTag("a1", body);

        assertThat(result.status()).isEqualTo("OPEN");
        verify(alarmService).get("a1");
    }
}
