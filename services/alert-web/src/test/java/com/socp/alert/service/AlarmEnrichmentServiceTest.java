package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.Severity;
import com.socp.alert.persistence.repository.AlarmRepository;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.client.service.ThreatClient;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlarmEnrichmentServiceTest {
    final AlarmRepository repository = mock(AlarmRepository.class);
    final ThreatClient threat = mock(ThreatClient.class);
    final AlarmEnrichmentCommitter commit = mock(AlarmEnrichmentCommitter.class);
    final AlarmEnrichmentService service = new AlarmEnrichmentService(repository, threat, commit, null);

    Alarm alarm() {
        Alarm alarm = new Alarm("r", "rule", Severity.HIGH, "connection 203.0.113.10", "203.0.113.10");
        alarm.setId("a"); alarm.setTenantId("tenant-a"); alarm.setInitialRiskScore(95);
        when(repository.findByTenantIdAndId("tenant-a", "a")).thenReturn(Optional.of(alarm));
        return alarm;
    }
    @Test void retriesUnavailableAndMalformedIntelligenceWithoutCommitting() {
        alarm();
        assertThatThrownBy(() -> service.enrichDurably("tenant-a", "a")).isInstanceOf(IllegalStateException.class);
        when(threat.matchIocs(anyString())).thenReturn(call("not-json"));
        assertThatThrownBy(() -> service.enrichDurably("tenant-a", "a")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(commit);
    }
    @Test void commitsBoundedHitsWithoutLoweringAdmissionRisk() {
        alarm();
        when(threat.matchIocs(anyString())).thenReturn(call("{\"code\":0,\"data\":{\"hits\":[{\"ioc\":\"203.0.113.10\"}]}}"));
        service.enrichDurably("tenant-a", "a");
        verify(commit).complete(eq("tenant-a"), eq("a"), contains("203.0.113.10"), eq(95));
        verify(repository, never()).save(any());
    }
    @Test void replayOfCommittedSnapshotDoesNotCallThreatAgain() {
        alarm().setEnrichedAt(Instant.now());
        service.enrichDurably("tenant-a", "a");
        verifyNoInteractions(threat, commit);
    }
    @Test void noIocStillCompletesTheDurableJob() {
        Alarm alarm = alarm(); alarm.setEntity(""); alarm.setMessage("plain text");
        service.enrichDurably("tenant-a", "a");
        verify(commit).complete("tenant-a", "a", "[]", 95);
        verifyNoInteractions(threat);
    }
    private static ServiceCall call(String body) {
        return new ServiceCall(SocpService.THREAT, "http://threat", true, 200, body, null, 1, false, 1);
    }
}
