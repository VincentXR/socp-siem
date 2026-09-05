package com.socp.soar.web.domain.v2;

import java.util.List;

public record DefinitionValidationResult(boolean valid, List<DefinitionIssue> errors,
                                         List<DefinitionIssue> warnings, String schemaVersion,
                                         String definitionHash, int nodeCount, int actionCount,
                                         int highRiskActionCount) {
    public DefinitionValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
