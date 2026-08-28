package com.socp.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the versioned investigation fixtures through the real evidence composer. */
class InvestigationDatasetEvaluationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    @Test
    void versionedCasesProduceGroundedHumanGatedResults() throws Exception {
        Path repository = repositoryRoot();
        Map<String, Object> dataset = JSON.readValue(
                repository.resolve("build/datasets/investigation-v1.json").toFile(), MAP);
        List<Map<String, Object>> output = new ArrayList<>();

        for (Map<String, Object> testCase : maps(dataset.get("cases"))) {
            String caseId = String.valueOf(testCase.get("id"));
            String alertId = String.valueOf(testCase.get("alertId"));
            Map<String, Object> alert = new LinkedHashMap<>(map(testCase.get("alert")));
            alert.put("id", alertId);
            List<Map<String, Object>> evidence = maps(testCase.get("evidence"));
            List<Map<String, Object>> related = evidence.stream().map(item -> {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("eventId", item.get("eventId"));
                event.put("timestamp", item.get("timestamp"));
                event.put("msg", item.get("raw"));
                return event;
            }).toList();
            List<String> iocValues = InvestigationEvidenceComposer.iocValues(alert, evidence);
            Map<String, Object> iocMatches = new LinkedHashMap<>();
            iocValues.forEach(value -> iocMatches.put(value, Map.of("matched", true)));
            String query = InvestigationEvidenceComposer.searchQuery(alert, evidence);
            List<Map<String, Object>> citations = InvestigationEvidenceComposer.citations(
                    alertId, alert, evidence, related, List.of(), iocMatches);
            List<Map<String, Object>> timeline = InvestigationEvidenceComposer.timeline(alert, evidence, related);
            List<Map<String, Object>> actions = InvestigationEvidenceComposer.nextActions(query);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("alertId", alertId);
            result.put("relatedEvents", related);
            result.put("iocMatches", iocMatches);
            result.put("citations", citations);
            result.put("timeline", timeline);
            result.put("nextActions", actions);
            result.put("analysis", InvestigationEvidenceComposer.deterministicAnalysis(
                    alert, evidence, related, List.of()));
            output.add(Map.of("caseId", caseId, "result", result));

            assertThat(citations).anyMatch(item -> ("alert:" + alertId).equals(item.get("id")));
            assertThat(citations).anyMatch(item -> String.valueOf(item.get("id")).startsWith("evidence:"));
            assertThat(actions).filteredOn(item -> "SOAR_SUGGESTION".equals(item.get("type")))
                    .allMatch(item -> "REQUIRES_HUMAN_APPROVAL".equals(item.get("status"))
                            && Boolean.FALSE.equals(item.get("executable")));
        }

        Path resultFile = repository.resolve(
                "services/ai-assistant/target/investigation-eval-results.json");
        Files.createDirectories(resultFile.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), Map.of("results", output));
        assertThat(output).hasSizeGreaterThanOrEqualTo(3);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("build/datasets/investigation-v1.json"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.of();
    }
}
