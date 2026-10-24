package com.socp.gateway.filter;

import com.socp.platform.auth.security.JwtValidator;
import com.socp.platform.obs.trace.TracePropagation;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The SDK is process-global; use a fresh JVM to exercise real startup without
 * changing the tracer installed by other propagation tests. */
class GatewayTracingStartupTest {
    @Test
    void springPropertyEnablesActualGatewaySpans() throws Exception {
        probe("socp.obs.tracing.enabled", true);
    }

    @Test
    void integrationEnvironmentFlagEnablesActualGatewaySpans() throws Exception {
        probe("SOCP_TRACING_ENABLED", true);
    }

    @Test
    void disabledTracingKeepsBusinessRequestsWorking() throws Exception {
        probe("socp.obs.tracing.enabled", false);
    }

    private static void probe(String property, boolean enabled) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, Probe.class.getName(), property, Boolean.toString(enabled))
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "gateway tracing startup probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            boolean enabled = Boolean.parseBoolean(args[1]);
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe",
                        Map.of(args[0], args[1], "spring.application.name", "api-gateway")));
                context.registerBean(JwtValidator.class, () -> mock(JwtValidator.class));
                context.register(GatewayFilter.class);
                context.refresh();
                var span = TracePropagation.startSpan("startup-probe", SpanKind.INTERNAL, Context.root());
                if (span.getSpanContext().isValid() != enabled || span.isRecording() != enabled)
                    throw new AssertionError("Configured gateway tracing did not initialize the SDK");
                span.end();
                var forwarded = new AtomicReference<String>();
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/auth/capabilities"));
                context.getBean(GatewayFilter.class).filter(exchange, request -> {
                    forwarded.set(request.getRequest().getHeaders().getFirst("traceparent"));
                    return Mono.empty();
                }).block(Duration.ofSeconds(3));
                // W3C flags can include the random-trace-id bit (03). Only
                // bit zero defines sampling; do not require the old literal 01.
                if (enabled && (forwarded.get() == null
                        || !forwarded.get().matches("00-[a-f0-9]{32}-[a-f0-9]{16}-[a-f0-9]{2}")
                        || (Integer.parseInt(forwarded.get().substring(53), 16) & 1) == 0))
                    throw new AssertionError("Gateway did not forward its actual span context: " + forwarded.get());
                if (exchange.getResponse().getStatusCode() != null)
                    throw new AssertionError("Tracing changed the public business route result");
            }
        }
    }
}
