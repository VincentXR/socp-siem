package com.socp.soar.web.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SoarConnectorServiceTest {
    @Test
    void rejectsPrivateAndNonAllowlistedConnectorEndpoints() {
        assertThrows(ResponseStatusException.class,
                () -> SoarConnectorService.validateEndpoint("http://example.com/hook", List.of("example.com")));
        assertThrows(ResponseStatusException.class,
                () -> SoarConnectorService.validateEndpoint("https://127.0.0.1/hook", List.of("127.0.0.1")));
        assertThrows(ResponseStatusException.class,
                () -> SoarConnectorService.validateEndpoint("https://example.com/hook", List.of("other.example")));
    }
}
