package com.socp.search.config.infrastructure.opensearch;

import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.service.SplEngine;

import com.sun.net.httpserver.HttpServer;
import com.socp.search.config.config.OpenSearchProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertNull;

class OsEventReaderTest {

    private HttpServer server;
    private OsEventReader reader;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        OpenSearchProperties properties = new OpenSearchProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setUsername("test");
        properties.setPassword("test");
        properties.setEnabled(true);
        properties.setSearchIndex("events-*");
        reader = new OsEventReader(properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void returnsNullWhenOpenSearchRespondsUnavailable() {
        assertNull(reader.search("source=auth", 50));
    }
}
