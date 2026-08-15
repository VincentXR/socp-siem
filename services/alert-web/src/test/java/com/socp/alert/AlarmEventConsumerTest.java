package com.socp.alert;

import com.socp.platform.client.IncidentClient;
import com.socp.platform.client.NotifyClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpService;
import com.socp.platform.client.SoarClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlarmEventConsumerTest {

    @Mock
    private CkReporter ckReporter;

    @Mock
    private NotifyClient notifyClient;

    @Mock
    private IncidentClient incidentClient;

    @Mock
    private SoarClient soarClient;

    @Test
    void fansOutAlarmToAnalyticsAndResponseServices() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(ckReporter, notifyClient, incidentClient, soarClient);
        ServiceCall ok = okCall();
        given(notifyClient.notifyAlert(org.mockito.ArgumentMatchers.anyString())).willReturn(ok);
        given(incidentClient.createFromAlarm(org.mockito.ArgumentMatchers.anyString())).willReturn(ok);
        given(soarClient.evaluate(org.mockito.ArgumentMatchers.anyString())).willReturn(ok);

        consumer.fanOut(alarm("AL-100"));

        verify(ckReporter).reportAlarm(org.mockito.ArgumentMatchers.any(Alarm.class));
        verify(notifyClient).notifyAlert(org.mockito.ArgumentMatchers.contains("AL-100"));
        verify(incidentClient).createFromAlarm(org.mockito.ArgumentMatchers.contains("AL-100"));
        verify(soarClient).evaluate(org.mockito.ArgumentMatchers.contains("AL-100"));
    }

    @Test
    void downstreamFailureDoesNotPreventOtherFanoutCalls() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(ckReporter, notifyClient, incidentClient, soarClient);
        ServiceCall failed = new ServiceCall(SocpService.NOTIFY, "http://notify", false,
                503, "", "unavailable", 2, true, 1);
        ServiceCall ok = okCall();
        given(notifyClient.notifyAlert(org.mockito.ArgumentMatchers.anyString())).willReturn(failed);
        given(incidentClient.createFromAlarm(org.mockito.ArgumentMatchers.anyString())).willReturn(ok);
        given(soarClient.evaluate(org.mockito.ArgumentMatchers.anyString())).willReturn(ok);

        consumer.fanOut(alarm("AL-101"));

        verify(incidentClient).createFromAlarm(org.mockito.ArgumentMatchers.contains("AL-101"));
        verify(soarClient).evaluate(org.mockito.ArgumentMatchers.contains("AL-101"));
    }

    private static ServiceCall okCall() {
        return new ServiceCall(SocpService.ALERT, "http://alert", true,
                200, "", null, 1, false, 1);
    }

    private static Map<String, Object> alarm(String id) {
        return Map.of(
                "id", id,
                "ruleId", "AUTH-BRUTE",
                "ruleName", "SSH brute force",
                "severity", "HIGH",
                "entity", "203.0.113.10",
                "message", "failed login",
                "occurredAt", "2026-08-15T00:00:00Z");
    }
}
