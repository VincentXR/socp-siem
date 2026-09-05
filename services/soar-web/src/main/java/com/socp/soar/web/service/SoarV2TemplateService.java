package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, reviewable golden playbook templates shipped as JSON resources. */
@Service
public class SoarV2TemplateService {
    private final ObjectMapper mapper;
    private final SoarV2Service soar;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public SoarV2TemplateService(ObjectMapper mapper, SoarV2Service soar) {
        this.mapper = mapper;
        this.soar = soar;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode template : templates()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", template.path("id").asText());
            view.put("version", template.path("version").asInt(1));
            view.put("name", template.path("name").asText());
            view.put("description", template.path("description").asText());
            view.put("eventTypes", mapper.convertValue(template.path("eventTypes"), List.class));
            view.put("requiredConnectors", mapper.convertValue(template.path("requiredConnectors"), List.class));
            view.put("risk", template.path("risk").asText("LOW"));
            view.put("attackTags", mapper.convertValue(template.path("attackTags"), List.class));
            view.put("testSample", mapper.convertValue(template.path("testSample"), Map.class));
            result.add(view);
        }
        return result;
    }

    /** Install creates a tenant-owned draft only; it never publishes or enables a rule. */
    public Map<String, Object> install(String id) {
        JsonNode template = templates().stream().filter(item -> id.equals(item.path("id").asText())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "template not found"));
        Map<String, Object> playbook = soar.createPlaybook(template.path("name").asText(),
                template.path("description").asText(),
                mapper.convertValue(template.path("attackTags"), List.class));
        String playbookId = String.valueOf(playbook.get("id"));
        int version = ((Number) playbook.getOrDefault("draftVersion", 1)).intValue();
        Map<String, Object> draft = soar.saveDraft(playbookId, version,
                template.path("definition").toString(), "{}", null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateId", id);
        result.put("templateVersion", template.path("version").asInt(1));
        result.put("playbook", playbook);
        result.put("draft", draft);
        result.put("published", false);
        result.put("ruleEnabled", false);
        return result;
    }

    private List<JsonNode> templates() {
        List<JsonNode> result = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources("classpath:/soar/templates/*.json");
            for (Resource resource : resources) {
                try (InputStream input = resource.getInputStream()) {
                    JsonNode node = mapper.readTree(input);
                    if (node != null && node.isObject() && !node.path("id").asText().isBlank()) result.add(node);
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("SOAR template catalog unavailable", failure);
        }
        result.sort(java.util.Comparator.comparing(item -> item.path("id").asText()));
        return result;
    }
}
