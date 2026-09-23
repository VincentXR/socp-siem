package com.socp.platform.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IngestBodyLimitAdviceTest {
    private final IngestBodyLimitAdvice advice = new IngestBodyLimitAdvice(
            new MockEnvironment().withProperty("socp.ingest.limits.max-body-bytes", "16"));

    @Test
    void stopsUnknownLengthStreamAfterLimitPlusOneByte() throws Exception {
        CountingMessage input = new CountingMessage(-1, 100_000);
        assertThatThrownBy(() -> read(input)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(413));
        assertThat(input.read).isEqualTo(17);
    }

    @Test
    void rejectsDeclaredOverflowWithoutReadingAndDoesNotTrustUnderreportedLength() throws Exception {
        CountingMessage declared = new CountingMessage(17, 100_000);
        assertThatThrownBy(() -> read(declared)).isInstanceOf(ResponseStatusException.class);
        assertThat(declared.read).isZero();
        CountingMessage underreported = new CountingMessage(1, 100_000);
        assertThatThrownBy(() -> read(underreported)).isInstanceOf(ResponseStatusException.class);
        assertThat(underreported.read).isEqualTo(17);
    }

    @Test
    void allowsExactBoundaryAndPreservesHeadersAndBytes() throws Exception {
        CountingMessage input = new CountingMessage(-1, 16);
        input.headers.setContentType(MediaType.TEXT_PLAIN);
        HttpInputMessage bounded = read(input);
        assertThat(bounded.getBody().readAllBytes()).containsExactly(new byte[16]);
        assertThat(bounded.getHeaders()).isSameAs(input.headers);
    }

    @Test
    void mvcRejectsBeforeJsonParsingAndCountsUtf8Bytes() throws Exception {
        Ingress controller = new Ingress();
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
        mvc.perform(post("/json").contentType(MediaType.APPLICATION_JSON).content("{".repeat(17)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/raw").contentType(MediaType.TEXT_PLAIN)
                        .content("\u00e9".repeat(9).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(controller.calls).isZero();
        mvc.perform(post("/raw").contentType(MediaType.TEXT_PLAIN).content("a".repeat(16)))
                .andExpect(status().isOk());
        mvc.perform(post("/ordinary").contentType(MediaType.TEXT_PLAIN).content("a".repeat(17)))
                .andExpect(status().isOk());
        assertThat(controller.calls).isEqualTo(2);
    }

    @Test
    void honorsClassAnnotationAndRejectsInvalidConfiguredBounds() throws Exception {
        MethodParameter parameter = new MethodParameter(ClassIngress.class.getDeclaredMethod("raw", String.class), 0);
        assertThat(advice.supports(parameter, String.class, StringHttpMessageConverter.class)).isTrue();
        assertThatThrownBy(() -> advice.beforeBodyRead(new CountingMessage(-1, 20), parameter,
                String.class, StringHttpMessageConverter.class)).isInstanceOf(ResponseStatusException.class);
        var invalid = new IngestBodyLimitAdvice(new MockEnvironment()
                .withProperty("socp.ingest.limits.max-body-bytes", "0"));
        assertThatThrownBy(() -> invalid.beforeBodyRead(new CountingMessage(-1, 1),
                new MethodParameter(Ingress.class.getDeclaredMethod("raw", String.class), 0),
                String.class, StringHttpMessageConverter.class)).isInstanceOf(IllegalStateException.class);
    }

    private HttpInputMessage read(HttpInputMessage input) throws Exception {
        return advice.beforeBodyRead(input, new MethodParameter(Ingress.class.getDeclaredMethod("raw", String.class), 0),
                String.class, StringHttpMessageConverter.class);
    }

    @Test
    void explicitLimitBoundsOrdinaryRoutesAndCannotRelaxCollectorLimit() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new BoundedIngress()).setControllerAdvice(advice).build();
        mvc.perform(post("/bounded").contentType(MediaType.TEXT_PLAIN).content("a".repeat(8)))
                .andExpect(status().isOk());
        mvc.perform(post("/bounded").contentType(MediaType.TEXT_PLAIN).content("a".repeat(9)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/both").contentType(MediaType.TEXT_PLAIN).content("a".repeat(17)))
                .andExpect(status().isPayloadTooLarge());
        var parameter = new MethodParameter(BoundedIngress.class.getDeclaredMethod("raw", String.class), 0);
        CountingMessage chunked = new CountingMessage(-1, 100_000);
        assertThatThrownBy(() -> advice.beforeBodyRead(chunked, parameter, String.class,
                StringHttpMessageConverter.class)).isInstanceOf(ResponseStatusException.class);
        assertThat(chunked.read).isEqualTo(9);
        var invalid = new MethodParameter(BoundedIngress.class.getDeclaredMethod("invalid", String.class), 0);
        assertThatThrownBy(() -> advice.beforeBodyRead(new CountingMessage(-1, 1), invalid,
                String.class, StringHttpMessageConverter.class)).isInstanceOf(IllegalStateException.class);
    }

    @RestController
    @RequestBodyLimit(maxBytes = 8)
    static class BoundedIngress {
        @PostMapping("/bounded")
        public String raw(@RequestBody String body) { return body; }
        @RequireIngestIdentity @RequestBodyLimit(maxBytes = 32) @PostMapping("/both")
        public String both(@RequestBody String body) { return body; }
        @RequestBodyLimit(maxBytes = 0)
        public String invalid(String body) { return body; }
    }

    @RestController
    static class Ingress {
        int calls;
        @RequireIngestIdentity @PostMapping("/raw")
        public String raw(@RequestBody String body) { calls++; return body; }
        @RequireIngestIdentity @PostMapping("/json")
        public Map<String, Object> json(@RequestBody Map<String, Object> body) { calls++; return body; }
        @PostMapping("/ordinary")
        public String ordinary(@RequestBody String body) { calls++; return body; }
    }

    @RequireIngestIdentity(maxBodyBytes = "8")
    static class ClassIngress {
        public String raw(String body) { return body; }
    }

    private static class CountingMessage implements HttpInputMessage {
        final HttpHeaders headers = new HttpHeaders();
        int read;
        final int length;
        CountingMessage(long declared, int length) {
            if (declared >= 0) headers.setContentLength(declared);
            this.length = length;
        }
        @Override public HttpHeaders getHeaders() { return headers; }
        @Override public InputStream getBody() throws IOException {
            return new InputStream() {
                @Override public int read() { return read < length ? advance() : -1; }
                private int advance() { read++; return 0; }
            };
        }
    }
}
