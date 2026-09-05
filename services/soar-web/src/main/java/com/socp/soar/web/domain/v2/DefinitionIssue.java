package com.socp.soar.web.domain.v2;

public record DefinitionIssue(String severity, String code, String nodeId, String path, String message) {
    public static DefinitionIssue error(String code, String nodeId, String path, String message) {
        return new DefinitionIssue("ERROR", code, nodeId, path, message);
    }

    public static DefinitionIssue warning(String code, String nodeId, String path, String message) {
        return new DefinitionIssue("WARNING", code, nodeId, path, message);
    }
}
