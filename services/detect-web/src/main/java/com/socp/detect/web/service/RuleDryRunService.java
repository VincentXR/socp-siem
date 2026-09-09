package com.socp.detect.web.service;

import com.socp.detect.web.api.request.RuleDryRunRequest;
import com.socp.detect.web.persistence.store.DetectionContentCatalog;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.config.RuleSpec;
import com.socp.rule.model.Alert;
import com.socp.rule.rules.Rule;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fresh rule instances only: no live engine, journal, suppression or output sinks. */
@Service
public class RuleDryRunService {
    public List<Map<String, Object>> evaluate(RuleDryRunRequest request) {
        String tenant = TenantContext.require();
        var events = request.events().stream().map(event -> event.toSecurityEvent(tenant)).toList();
        List<Map<String, Object>> results = new ArrayList<>();
        for (var input : request.rules()) {
            var document = DetectionContentCatalog.enrich(input.asMap());
            var errors = DetectionContentCatalog.validateSpec(document);
            if (!errors.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
            try {
                RuleSpec spec = new RuleSpec(document);
                try (Rule rule = spec.toRule()) {
                    List<Alert> alerts = new ArrayList<>();
                    for (var event : events) {
                        rule.accept(event);
                        alerts.addAll(rule.drain());
                    }
                    results.add(Map.of("id", spec.id, "name", spec.name, "type", spec.type,
                            "matched", !alerts.isEmpty(), "alerts", alerts, "eventCount", events.size()));
                }
            } catch (IllegalArgumentException failure) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid rule: " + failure.getMessage());
            }
        }
        return results;
    }
}
