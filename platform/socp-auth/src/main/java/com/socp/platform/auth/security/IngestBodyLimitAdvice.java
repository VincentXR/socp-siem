package com.socp.platform.auth.security;

import org.springframework.core.MethodParameter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/** Bounds authenticated ingress before JSON or String converters allocate the body. */
@ControllerAdvice
public class IngestBodyLimitAdvice extends RequestBodyAdviceAdapter {
    private final Environment environment;

    public IngestBodyLimitAdvice(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return annotation(parameter) != null || bodyLimit(parameter) != null;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter,
                                          Type targetType,
                                          Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        RequireIngestIdentity ingest = annotation(parameter);
        RequestBodyLimit explicit = bodyLimit(parameter);
        int limit = ingest == null ? 64 * 1024 * 1024
                : Integer.parseInt(environment.resolveRequiredPlaceholders(ingest.maxBodyBytes()));
        if (limit < 1 || limit > 64 * 1024 * 1024) {
            throw new IllegalStateException("Ingest raw body limit must be between 1 and 67108864 bytes");
        }
        if (explicit != null) {
            if (explicit.maxBytes() < 1 || explicit.maxBytes() > 64 * 1024 * 1024)
                throw new IllegalStateException("Raw body limit must be between 1 and 67108864 bytes");
            limit = Math.min(limit, explicit.maxBytes());
        }
        if (input.getHeaders().getContentLength() > limit) throw oversized(limit);
        // Read one extra byte to detect overflow even for chunked/unknown-length bodies.
        // Throw here, outside Jackson, so overflow retains HTTP 413 instead of becoming 400.
        byte[] bytes = input.getBody().readNBytes(limit + 1);
        if (bytes.length > limit) throw oversized(limit);
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }

            @Override
            public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }

    private static RequireIngestIdentity annotation(MethodParameter parameter) {
        RequireIngestIdentity method = parameter.getMethodAnnotation(RequireIngestIdentity.class);
        return method != null ? method
                : parameter.getContainingClass().getAnnotation(RequireIngestIdentity.class);
    }

    private static RequestBodyLimit bodyLimit(MethodParameter parameter) {
        RequestBodyLimit method = parameter.getMethodAnnotation(RequestBodyLimit.class);
        return method != null ? method : parameter.getContainingClass().getAnnotation(RequestBodyLimit.class);
    }

    private static ResponseStatusException oversized(int limit) {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "ingest body exceeds " + limit + " bytes");
    }
}
