package com.socp.search.config.search;

import com.sun.net.httpserver.HttpServer;
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

        reader = new OsEventReader();
        ReflectionTestUtils.setField(reader, "url", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(reader, "username", "test");
        ReflectionTestUtils.setField(reader, "password", "test");
        ReflectionTestUtils.setField(reader, "enabled", true);
        ReflectionTestUtils.setField(reader, "index", "events-*");
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
