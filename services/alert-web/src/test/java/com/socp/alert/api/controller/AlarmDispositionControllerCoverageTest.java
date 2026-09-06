package com.socp.alert.api.controller;

import com.socp.alert.api.request.AlarmAssignmentRequest;
import com.socp.alert.api.request.AlarmNoteRequest;
import com.socp.alert.api.request.AlarmStatusRequest;
import com.socp.alert.api.request.AlarmTagRequest;
import com.socp.alert.service.AlarmDispositionService;
import com.socp.alert.service.AlarmService;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Controller delegation and validation for the alarm disposition endpoints. */
@ExtendWith(MockitoExtension.class)
class AlarmDispositionControllerCoverageTest {

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
    void getVerifiesTheAlarmExistsThenReturnsTheDisposition() {
        given(disposition.get("a1")).willReturn(open());

        AlarmDispositionService.Disposition result = controller.get("a1");

        assertThat(result.status()).isEqualTo("OPEN");
        verify(alarmService).get("a1");
    }

    @Test
    void setStatusVerifiesTheAlarmThenDelegatesTheBodyValue() {
        given(disposition.setStatus("a1", "RESOLVED")).willReturn(open());
        AlarmStatusRequest body = new AlarmStatusRequest("RESOLVED");

        AlarmDispositionService.Disposition result = controller.setStatus("a1", body);

        assertThat(result.status()).isEqualTo("OPEN");
        verify(alarmService).get("a1");
    }

    @Test
    void assignDelegatesWhenTheAssigneeIsPresent() {
        given(disposition.assign("a1", "bob")).willReturn(open());
        AlarmAssignmentRequest body = new AlarmAssignmentRequest("bob");

        AlarmDispositionService.Disposition result = controller.assign("a1", body);

        assertThat(result.status()).isEqualTo("OPEN");
        verify(alarmService).get("a1");
    }

    @Test
    void assignRejectsBlankAssigneesBeforeTouchingTheDispositionService() {
        AlarmAssignmentRequest body = new AlarmAssignmentRequest("   ");

        assertThatThrownBy(() -> controller.assign("a1", body))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("assignee")
                .extracting("code").isEqualTo(400);

        verify(alarmService).get("a1");
        verify(disposition, never()).assign(anyString(), anyString());
    }

    @Test
    void addNoteFallsBackToTheOperatorAuthorAndForwardsTheIdempotencyKey() {
        given(disposition.addNote("a1", "operator", "enriched", "run-1:key-1")).willReturn(open());
        AlarmNoteRequest body = new AlarmNoteRequest("  ", "enriched");

        AlarmDispositionService.Disposition result = controller.addNote("a1", body, "run-1:key-1");

        assertThat(result.status()).isEqualTo("OPEN");
        verify(alarmService).get("a1");
    }

    @Test
    void addNoteKeepsANonBlankAuthorAndPassesAMissingHeaderAsNull() {
        given(disposition.addNote("a1", "alice", "note", null)).willReturn(open());
        AlarmNoteRequest body = new AlarmNoteRequest("alice", "note");

        AlarmDispositionService.Disposition result = controller.addNote("a1", body, null);

        assertThat(result.status()).isEqualTo("OPEN");
        verify(alarmService).get("a1");
    }

    @Test
    void addTagDelegatesTheRequestBodyTag() {
        given(disposition.addTag("a1", "prod")).willReturn(open());
        AlarmTagRequest body = new AlarmTagRequest("prod");

        AlarmDispositionService.Disposition result = controller.addTag("a1", body);

        assertThat(result.status()).isEqualTo("OPEN");
        verify(alarmService).get("a1");
    }
}
