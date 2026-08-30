package com.socp.platform.error;

import com.socp.platform.error.exception.ApiException;
import com.socp.platform.error.web.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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
    void mapsBusinessAndUnexpectedErrorsToSafeResponses() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        var limited = handler.handleApi(ApiException.tooManyRequests("slow", 7));
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("7");

        var domain = handler.handleApi(ApiException.of(10001, "domain failure"));
        assertThat(domain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(domain.getBody().code()).isEqualTo(10001);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/test");
        var unexpected = handler.handleOther(new RuntimeException(), request);
        assertThat(unexpected.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unexpected.getBody().message()).isEqualTo("internal error");
    }
}
