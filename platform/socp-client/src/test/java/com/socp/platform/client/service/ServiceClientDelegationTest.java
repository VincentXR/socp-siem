package com.socp.platform.client.service;

import com.socp.platform.client.http.SocpHttpClient;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ServiceClientDelegationTest {

    private final SocpHttpClient http = mock(SocpHttpClient.class);

    @Test
    void delegatesAllAlertInvestigationCalls() {
        AlertClient client = new AlertClient(http);

        client.forwardAlarm("alarm");
        client.stats();
        client.stats("7d");
        client.stats(" ");
        client.getAlarm("alarm/1");
        client.evidence("alarm/1");

        verify(http).postJson(SocpService.ALERT, "/api/alarms", "alarm");
        verify(http, times(2)).get(SocpService.ALERT, "/api/alarms/stats");
        verify(http).get(SocpService.ALERT, "/api/alarms/stats?window=7d");
        verify(http).get(SocpService.ALERT, "/api/alarms/alarm%2F1");
        verify(http).get(SocpService.ALERT, "/api/alarms/alarm%2F1/evidence");
    }

    @Test
    void delegatesDomainClientsWithTheirStableContracts() {
        new AssetClient(http).collect("asset");
        new DetectClient(http).ingestBulk("event");
        new HipsClient(http).reportEvent("hips");
        new NotifyClient(http).notifyAlert("notify");
        new SoarClient(http).evaluate("soar");
        new ThreatClient(http).matchIocs("[\"1.2.3.4\"]");
        new SearchClient(http).search("host = \"web 1\"");

        verify(http).postJson(SocpService.ASSET, "/api/v1/assets/collect", "asset");
        verify(http).post(SocpService.DETECT, "/api/v1/ingest/bulk", "event",
                SocpHttpClient.NDJSON, 5000);
        verify(http).postJson(SocpService.HIPS, "/api/v1/endpoints/events", "hips");
        verify(http).postJson(SocpService.NOTIFY, "/api/v1/notify/alert", "notify");
        verify(http).postJson(SocpService.SOAR, "/api/v1/playbooks/evaluate", "soar");
        verify(http).postJson(SocpService.THREAT, "/api/v1/iocs/match", "[\"1.2.3.4\"]");
        verify(http).get(SocpService.SEARCH, "/api/v1/search?q=host%20%3D%20%22web%201%22");
    }

    @Test
    void delegatesIncidentCorrelationAndEscapesQueryParameters() {
        IncidentClient client = new IncidentClient(http);

        client.createFromAlarm("alarm");
        client.list();
        client.addNote("CASE/1", "analyst one", "summary & evidence");

        verify(http).postJson(SocpService.INCIDENT, "/api/v1/incidents/from-alarm", "alarm");
        verify(http).get(SocpService.INCIDENT, "/api/v1/incidents");
        verify(http).postJson(SocpService.INCIDENT,
                "/api/v1/incidents/CASE%2F1/notes?author=analyst%20one&content=summary%20%26%20evidence",
                "{}");
    }
}
