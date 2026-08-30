package com.socp.detect.web.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SigmaRuleImporterTest {

    private final SigmaRuleImporter importer = new SigmaRuleImporter();

    @Test
    void convertsLosslessScalarSelectionAndPreservesSource() {
        String yaml = """
                title: PowerShell process
                id: sigma-powershell-1
                status: stable
                level: high
                description: Detect PowerShell
                tags: [attack.t1059.001]
                logsource:
                  product: windows
                  service: sysmon
                detection:
                  selection:
                    EventID: 1
                    Image|endswith: powershell.exe
                  condition: selection
                """;

        SigmaRuleImporter.ImportResult result = importer.importRule(yaml);
        Map<String, Object> spec = result.spec();
        assertEquals("sigma-powershell-1", spec.get("id"));
        assertEquals("ACTIVE", spec.get("status"));
        assertEquals("HIGH", spec.get("severity"));
        assertEquals(2, ((java.util.List<?>) spec.get("match")).size());
        assertEquals("process_executable", ((Map<?, ?>) ((java.util.List<?>) spec.get("match")).get(1)).get("field"));
        assertEquals(yaml, spec.get("sigmaSource"));
        assertEquals("T1059.001", spec.get("mitre"));
    }

    @Test
    void supportsAndOrAndOneOfWithoutChangingMeaning() {
        String yaml = """
                title: Multi selection
                detection:
                  first:
                    CommandLine|contains: whoami
                  second:
                    User: alice
                  condition: first and second
                """;
        assertEquals(2, importer.importRule(yaml).selections().size());

        String or = yaml.replace("first and second", "first or second");
        assertEquals(2, ((java.util.List<?>) importer.importRule(or).spec().get("matchAny")).size());

        String oneOf = yaml.replace("first and second", "1 of them");
        assertEquals(2, ((java.util.List<?>) importer.importRule(oneOf).spec().get("matchAny")).size());

        String allOf = yaml.replace("first and second", "all of them");
        assertEquals(2, importer.importRule(allOf).spec().get("match") instanceof java.util.List<?> list
                ? list.size() : 0);
    }

    @Test
    void rejectsListsAndUnknownModifiersInsteadOfChangingMeaning() {
        String list = """
                title: list
                detection:
                  selection:
                    EventID: [1, 2]
                  condition: selection
                """;
        assertThrows(IllegalArgumentException.class, () -> importer.importRule(list));

        String modifier = list.replace("EventID: [1, 2]", "EventID|windownot: 1");
        assertThrows(IllegalArgumentException.class, () -> importer.importRule(modifier));
    }

    @Test
    void acceptsVersionedGoldenFixturesAcrossCommonSigmaLogsources() {
        String[] products = {"windows", "linux", "aws", "azure", "gcp"};
        String[] fields = {"Image", "CommandLine", "SourceIp", "User", "Computer"};
        for (int i = 0; i < 20; i++) {
            String field = fields[i % fields.length];
            String value = switch (field) {
                case "Image" -> "powershell.exe";
                case "CommandLine" -> "whoami";
                case "SourceIp" -> "203.0.113." + (i + 1);
                case "User" -> "analyst";
                default -> "host-" + i;
            };
            String modifier = field.equals("CommandLine") ? "|contains" : "";
            String yaml = """
                    title: Golden Sigma fixture %d
                    id: SIGMA-GOLDEN-%02d
                    status: test
                    level: medium
                    logsource:
                      product: %s
                      service: security
                    detection:
                      selection:
                        %s%s: %s
                      condition: selection
                    """.formatted(i, i, products[i % products.length], field, modifier, value);
            SigmaRuleImporter.ImportResult result = importer.importRule(yaml);
            assertEquals("TESTING", result.spec().get("status"));
            assertEquals("SIGMA-GOLDEN-" + String.format("%02d", i), result.ruleId());
            assertEquals(1, ((java.util.List<?>) result.spec().get("match")).size());
        }
    }

    @Test
    void validatesDocumentShapeSizeIdentityAndRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> importer.importRule(null));
        assertThrows(IllegalArgumentException.class, () -> importer.importRule("   "));
        assertThrows(IllegalArgumentException.class, () -> importer.importRule("[not a mapping"));
        assertThrows(IllegalArgumentException.class, () -> importer.importRule("null"));
        assertThrows(IllegalArgumentException.class, () -> importer.importRule("title: missing detection"));
        assertThrows(IllegalArgumentException.class, () -> importer.importRule("x".repeat(512 * 1024 + 1)));

        String base = """
                title: Invalid id
                id: "not a safe id"
                detection:
                  selection: {field: value}
                  condition: selection
                """;
        assertThrows(IllegalArgumentException.class, () -> importer.importRule(base));
    }

    @Test
    void supportsModifiersMetadataLifecycleAndTimeframeVariants() {
        String yaml = """
                title: Modifier coverage
                status: deprecated
                level: unknown
                sigma_version: 1.0
                timeframe: 1500ms
                tags: [attack.t1059, unrelated]
                logsource: {category: process_creation}
                detection:
                  selection:
                    Image|startswith: powershell
                    CommandLine|endswith: ".exe"
                    User|re: "^admin"
                  condition: selection
                """;
        Map<String, Object> spec = importer.importRule(yaml).spec();
        assertEquals("ARCHIVED", spec.get("status"));
        assertEquals("MEDIUM", spec.get("severity"));
        assertEquals("1s", spec.get("window"));
        assertEquals("T1059", spec.get("mitre"));
        assertEquals(List.of("process_creation"), spec.get("dataSources"));
        assertEquals("1.0", spec.get("sigmaVersion"));
    }

    @Test
    void rejectsUnsupportedConditionsValuesAndTimeframes() {
        String base = """
                title: Unsupported
                detection:
                  first: {Message: test}
                  second: {User: bob}
                  condition: first and second
                """;
        assertThrows(IllegalArgumentException.class,
                () -> importer.importRule(base.replace("first and second", "first and not second")));
        assertThrows(IllegalArgumentException.class,
                () -> importer.importRule(base.replace("first and second", "2 of them")));
        assertThrows(IllegalArgumentException.class,
                () -> importer.importRule(base.replace("first and second", "1 of missing*")));
        assertThrows(IllegalArgumentException.class,
                () -> importer.importRule(base.replace("first and second", "count(first) > 1")));
        assertThrows(IllegalArgumentException.class,
                () -> importer.importRule(base.replace("Message: test", "Message|windownot: test")));
        assertThrows(IllegalArgumentException.class,
                () -> importer.importRule(base.replace("condition: first and second", "condition: first")
                        .replace("title: Unsupported", "title: Unsupported\ntimeframe: 2w")));
        assertThrows(IllegalArgumentException.class,
                () -> importer.importRule(base.replace("Message: test", "Message: [test]")));
    }

    @Test
    void mapsCommonFieldsAndUsesFallbackMetadata() {
        String yaml = """
                title: Field mappings
                level: low
                logsource: {}
                detection:
                  selection:
                    EventId: 1
                    Executable: cmd.exe
                    process.command_line|contains: whoami
                    ComputerName: host
                    UserName: alice
                    SourceIP: 10.0.0.1
                    DestinationIP: 10.0.0.2
                    custom.field: value
                  condition: selection
                """;
        List<?> match = (List<?>) importer.importRule(yaml).spec().get("match");
        assertEquals(8, match.size());
        assertEquals(List.of("sigma"), importer.importRule(yaml).spec().get("dataSources"));
    }
}
