package com.socp.threat.web.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StixIndicatorImporterTest {

    private final StixIndicatorImporter importer = new StixIndicatorImporter();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "ipv4-addr,2001:db8::1", "ipv6-addr,192.0.2.1", "ipv4-addr,::ffff:192.0.2.1"
    })
    void rejectsDeclaredAddressFamilyMismatchForStixAndTaxii(String family, String value) {
        String bundle = "{\"objects\":[{\"type\":\"indicator\",\"pattern\":\"[" + family
                + ":value = '" + value + "']\"}]}";
        assertThrows(IllegalArgumentException.class, () -> importer.parse(bundle, "feed"));
        assertThrows(IllegalArgumentException.class, () -> importer.parseTaxiiEnvelope(bundle, "feed"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "ipv4-addr,192.0.2.1", "ipv6-addr,2001:db8::1", "ipv6-addr,::ffff:192.0.2.1"
    })
    void acceptsMatchingLiteralFamiliesIncludingIpv4MappedIpv6(String family, String value) {
        String bundle = "{\"objects\":[{\"type\":\"indicator\",\"pattern\":\"[" + family
                + ":value = '" + value + "']\"}]}";
        assertEquals(value, importer.parse(bundle, "feed").indicators().getFirst().value());
        assertEquals(value, importer.parseTaxiiEnvelope(bundle, "feed").indicators().getFirst().value());
    }

    @Test
    void stixAndTaxiiRejectNonLiteralIpIndicators() {
        String bundle = """
                {"objects":[{"type":"indicator","id":"indicator--invalid-ip",
                  "pattern":"[ipv4-addr:value = 'not-an-ip']"}]}
                """;
        assertThrows(IllegalArgumentException.class, () -> importer.parse(bundle, "feed"));
        assertThrows(IllegalArgumentException.class, () -> importer.parseTaxiiEnvelope(bundle, "feed"));
    }

    @Test
    void emptyTaxiiEnvelopeIsLegalButMalformedObjectsAndEmptyStixBundleRemainInvalid() {
        assertEquals(0, importer.parseTaxiiEnvelope("{}", "feed").indicators().size());
        assertThrows(IllegalArgumentException.class, () -> importer.parseTaxiiEnvelope("{\"objects\":null}", "feed"));
        assertThrows(IllegalArgumentException.class, () -> importer.parseTaxiiEnvelope("{\"objects\":{}}", "feed"));
        assertThrows(IllegalArgumentException.class, () -> importer.parse("{}", "feed"));
    }

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
