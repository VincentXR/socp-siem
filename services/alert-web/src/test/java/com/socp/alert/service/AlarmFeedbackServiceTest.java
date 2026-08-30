package com.socp.alert.service;

import com.socp.alert.persistence.entity.AlarmFeedbackEntity;
import com.socp.alert.repository.AlarmFeedbackRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlarmFeedbackServiceTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void savesFeedbackWithTenantAndNormalizesKind() {
        AlarmFeedbackRepository repository = mock(AlarmFeedbackRepository.class);
        when(repository.findByTenantIdAndAlarmIdAndKind("tenant-a", "alarm-1", "FALSE_POSITIVE"))
                .thenReturn(Optional.empty());
        when(repository.save(any(AlarmFeedbackEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlarmFeedbackService service = new AlarmFeedbackService(repository);

        var result = service.save("alarm-1", "false_positive", "known scanner",
                Instant.now().plusSeconds(3600), "analyst");

        assertEquals("tenant-a", result.get("tenantId"));
        assertEquals("FALSE_POSITIVE", result.get("kind"));
        assertEquals("known scanner", result.get("reason"));
    }

    @Test
    void rejectsExpiredFeedbackAndKeepsTenantReadScoped() {
        AlarmFeedbackRepository repository = mock(AlarmFeedbackRepository.class);
        AlarmFeedbackService service = new AlarmFeedbackService(repository);

        assertThrows(ApiException.class, () -> service.save("alarm-1", "RULE_EXCEPTION", "expired",
                Instant.now().minusSeconds(1), "analyst"));
        when(repository.findByTenantIdAndAlarmIdOrderByCreatedAtDesc("tenant-a", "alarm-1"))
                .thenReturn(List.of());
        assertEquals(List.of(), service.list("alarm-1"));
    }
}
