package com.socp.soar.web.service;

import com.socp.platform.tenant.TenantContext;
import com.socp.soar.web.store.AlarmEvaluationEntity;
import com.socp.soar.web.store.AlarmEvaluationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlarmEvaluationServiceTest {

    @Mock private PlaybookExecutor executor;
    @Mock private AlarmEvaluationRepository repository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void completedEvaluationIsReturnedWithoutExecutingAgain() {
        TenantContext.set("tenant-a");
        AlarmEvaluationEntity receipt = receipt("COMPLETED");
        receipt.setResultJson("{\"alarmId\":\"AL-1\",\"triggered\":1}");
        given(repository.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.of(receipt));
        AlarmEvaluationService service = new AlarmEvaluationService(executor, repository);

        Map<String, Object> result = service.evaluate(Map.of("id", "AL-1"));

        assertTrue((Boolean) result.get("duplicate"));
        verify(executor, never()).evaluate(any());
    }

    @Test
    void newEvaluationReservesBeforeExecutionAndPersistsResult() {
        TenantContext.set("tenant-a");
        given(repository.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        given(repository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(executor.evaluate(any())).willReturn(Map.of("alarmId", "AL-1", "triggered", 0));
        AlarmEvaluationService service = new AlarmEvaluationService(executor, repository);

        service.evaluate(Map.of("id", "AL-1"));

        ArgumentCaptor<AlarmEvaluationEntity> completed = ArgumentCaptor.forClass(AlarmEvaluationEntity.class);
        verify(repository).save(completed.capture());
        assertEquals("COMPLETED", completed.getValue().getStatus());
        assertEquals("tenant-a", completed.getValue().getTenantId());
    }

    @Test
    void concurrentRecentEvaluationReturnsRetryableFailure() {
        TenantContext.set("tenant-a");
        AlarmEvaluationEntity receipt = receipt("PROCESSING");
        given(repository.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.of(receipt));
        AlarmEvaluationService service = new AlarmEvaluationService(executor, repository);

        assertThrows(AlarmEvaluationService.EvaluationInProgressException.class,
                () -> service.evaluate(Map.of("id", "AL-1")));
    }

    private static AlarmEvaluationEntity receipt(String status) {
        AlarmEvaluationEntity receipt = new AlarmEvaluationEntity();
        receipt.setId("receipt-1");
        receipt.setTenantId("tenant-a");
        receipt.setAlarmId("AL-1");
        receipt.setStatus(status);
        receipt.setCreatedAt(Instant.now());
        receipt.setUpdatedAt(Instant.now());
        return receipt;
    }
}
