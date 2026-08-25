package com.socp.platform.client.http;




import com.socp.platform.client.service.SocpService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceCallTest {

    @Test
    void exposesStableLabelsAndFailureReasons() {
        ServiceCall success = new ServiceCall(SocpService.ALERT, "/alerts", true,
                200, "ok", null, 1, false, 1);
        ServiceCall error = new ServiceCall(null, "/external", false,
                503, "", null, 1, true, 2);

        assertThat(success.targetLabel()).isEqualTo("alert-web");
        assertThat(success.failureReason()).isNull();
        assertThat(error.targetLabel()).isEqualTo("external");
        assertThat(error.failureReason()).isEqualTo("HTTP 503");
    }
}
