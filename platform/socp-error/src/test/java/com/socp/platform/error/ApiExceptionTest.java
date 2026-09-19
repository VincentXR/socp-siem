package com.socp.platform.error;

import com.socp.platform.error.exception.ApiException;
import com.socp.platform.error.web.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiExceptionTest {

    @Test
    void exposesFactoryCodesAndRetryAfterMetadata() {
        assertThat(ApiException.badRequest("bad").getCode()).isEqualTo(400);
        assertThat(ApiException.unauthorized("auth").getCode()).isEqualTo(401);
        assertThat(ApiException.forbidden("forbidden").getCode()).isEqualTo(403);
        assertThat(ApiException.notFound("missing").getCode()).isEqualTo(404);
        assertThat(ApiException.tooManyRequests("slow").getCode()).isEqualTo(429);
        ApiException limited = ApiException.tooManyRequests("slow", 7);
        assertThat(limited.getCode()).isEqualTo(429);
        assertThat(limited.getRetryAfterSeconds()).isEqualTo(7);
        assertThat(ApiException.of(10001, "domain").getCode()).isEqualTo(10001);
    }

    @Test
    void mapsBusinessCodesAndRateLimitMetadata() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        var limited = handler.handleApi(ApiException.tooManyRequests("slow", 7));
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        assertThat(limited.getBody().message()).isEqualTo("slow");

        var domain = handler.handleApi(ApiException.of(10001, "domain failure"));
        assertThat(domain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(domain.getBody().code()).isEqualTo(10001);
        assertThat(domain.getBody().message()).isEqualTo("domain failure");
    }

    @Test
    void keepsDomainSentenceWhenApiExceptionCarriesOne() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        var forbidden = handler.handleApi(ApiException.forbidden("需要 alarm:triage 权限"));
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody().message()).isEqualTo("需要 alarm:triage 权限");
    }

    @Test
    void unexpectedFailuresKeepTheirSentenceOutOfTheEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/test");

        var withoutMessage = handler.handleOther(new RuntimeException(), request);
        var withMessage = handler.handleOther(
                new IllegalStateException("JdbcSQLSyntaxErrorException: select * from t_alarm failed at 10.0.0.5:5432"),
                request);

        for (var response : List.of(withoutMessage, withMessage)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().code()).isEqualTo(500);
            assertThat(response.getBody().message())
                    .isEqualTo(withoutMessage.getBody().message())
                    .doesNotContain("RuntimeException")
                    .doesNotContain("JdbcSQL")
                    .doesNotContain("select")
                    .doesNotContain("5432");
        }
        assertThat(withoutMessage.getBody().message()).contains("重试");
    }
}
