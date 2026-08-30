package com.socp.threat.web.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StixIndicatorImporterTest {

    private final StixIndicatorImporter importer = new StixIndicatorImporter();

    @Test
    void parsesStixIndicatorAndRetainsOperationalMetadata() {
        String bundle = """
                {"type":"bundle","id":"bundle--1","objects":[
                  {"type":"indicator","id":"indicator--abc","pattern":"[ipv4-addr:value = '203.0.113.9']",
                   "labels":["malware","tlp:amber","severity-high"],"confidence":87,
                   "valid_from":"2026-01-01T00:00:00Z","valid_until":"2027-01-01T00:00:00Z",
                   "revoked":true,"description":"test indicator"},
                  {"type":"relationship","id":"relationship--x"}]}
                """;
        var result = importer.parse(bundle, "unit-feed");
        assertEquals(1, result.indicators().size());
        var ioc = result.indicators().get(0);
        assertEquals("IP", ioc.type());
        assertEquals("203.0.113.9", ioc.value());
        assertEquals("unit-feed", ioc.source());
        assertEquals("indicator--abc", ioc.externalId());
        assertEquals(87d, ioc.confidence());
        assertEquals("tlp:amber", ioc.tlp());
        assertEquals(true, ioc.revoked());
        assertEquals(1, result.skipped());
    }

    @Test
    void rejectsUnsupportedObservableRatherThanCreatingWrongIoc() {
        String bundle = """
                {"type":"bundle","objects":[{"type":"indicator","id":"indicator--x",
                  "pattern":"[process:name = 'powershell.exe']"}]}
                """;
        assertThrows(IllegalArgumentException.class, () -> importer.parse(bundle, "feed"));
    }
}
