package com.socp.platform.client.config;

import com.socp.platform.client.http.ExternalEndpointPolicy;
import com.socp.platform.client.http.ServiceRequestSigner;
import com.socp.platform.client.http.ServiceTokenProvider;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.service.AssetClient;
import com.socp.platform.client.service.DetectClient;
import com.socp.platform.client.service.HipsClient;
import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.NotifyClient;
import com.socp.platform.client.service.SearchClient;
import com.socp.platform.client.service.SoarClient;
import com.socp.platform.client.service.ThreatClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers the shared inter-service HTTP client and its typed facades.
 *
 * <p>Business applications intentionally restrict their component scan to
 * their own domain package.  The client module therefore cannot rely on its
 * {@code @Component} annotations being discovered by application scanning;
 * this explicit auto-configuration keeps the dependency opt-in and auditable
 * while making every typed client available to applications that declare
 * {@code socp-client}.</p>
 */
@AutoConfiguration
@Import({
        ServiceEndpoints.class,
        SocpClientProperties.class,
        ExternalEndpointPolicy.class,
        ServiceRequestSigner.class,
        ServiceTokenProvider.class,
        SocpHttpClient.class,
        AlertClient.class,
        AssetClient.class,
        DetectClient.class,
        HipsClient.class,
        IncidentClient.class,
        NotifyClient.class,
        SearchClient.class,
        SoarClient.class,
        ThreatClient.class
})
public class SocpClientAutoConfiguration {
}
