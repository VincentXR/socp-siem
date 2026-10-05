package com.socp.platform.auth.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provisioned tenant membership, shared by directory reads and owning-service assignment checks. */
public final class OperatorDirectory {
    public record Operator(String id, String label, String role, boolean enabled) { }
    private final Map<String, Map<String, Operator>> tenants;

    public OperatorDirectory(String json, String localUsers, String localRoles) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            if (json != null && json.length() > 1_048_576) throw new IllegalArgumentException("directory exceeds 1 MiB");
            JsonNode root = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!root.isObject() || root.size() > 128) throw new IllegalArgumentException("directory must contain at most 128 tenants");
            Map<String, Map<String, Operator>> result = new LinkedHashMap<>();
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!TenantContext.isValid(field.getKey()) || !field.getValue().isArray() || field.getValue().size() > 512) {
                    throw new IllegalArgumentException("invalid tenant or directory size (maximum 512 members)");
                }
                Map<String, Operator> members = new LinkedHashMap<>();
                for (JsonNode member : field.getValue()) {
                    String id = member.path("id").asText("").trim();
                    String label = member.path("label").asText(id).trim();
                    String role = member.path("role").asText("analyst");
                    if (id.isBlank() || id.length() > 128 || id.startsWith("service:") || id.chars().anyMatch(Character::isISOControl)
                            || label.isBlank() || label.length() > 256 || !Permission.ISSUABLE_ROLES.contains(role)
                            || (member.has("enabled") && !member.get("enabled").isBoolean())) {
                        throw new IllegalArgumentException("invalid operator identity");
                    }
                    if (members.putIfAbsent(id, new Operator(id, label, role, member.path("enabled").asBoolean(true))) != null) {
                        throw new IllegalArgumentException("duplicate operator identity");
                    }
                }
                result.put(field.getKey(), Map.copyOf(members));
            }
            // Explicit tenant provisioning wins, including disabled local identities.
            if (!result.containsKey("default") && localUsers != null && !localUsers.isBlank()) {
                JsonNode users = mapper.readTree(localUsers);
                JsonNode roles = mapper.readTree(localRoles == null || localRoles.isBlank() ? "{}" : localRoles);
                if (!users.isObject() || users.size() > 512 || !roles.isObject()) throw new IllegalArgumentException("invalid local operator directory");
                Map<String, Operator> members = new LinkedHashMap<>();
                users.fieldNames().forEachRemaining(id -> members.put(id, new Operator(id, id, roles.path(id).asText("analyst"), true)));
                result.put("default", Map.copyOf(members));
            }
            tenants = Map.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid operator directory configuration", failure);
        }
    }

    public List<Operator> list(String tenant, String subject, String role) {
        Map<String, Operator> members = new LinkedHashMap<>(tenants.getOrDefault(tenant, Map.of()));
        if (subject != null && !subject.isBlank() && !subject.startsWith("service:") && !members.containsKey(subject)) {
            members.put(subject, new Operator(subject, subject, role, true));
        }
        List<Operator> result = new ArrayList<>();
        members.values().stream().filter(OperatorDirectory::assignable).forEach(result::add);
        result.sort(Comparator.comparing(Operator::label, String.CASE_INSENSITIVE_ORDER).thenComparing(Operator::id));
        return List.copyOf(result);
    }

    public boolean configured(String tenant) { return tenants.containsKey(tenant); }

    public void requireAssignable(String assignee) {
        if (assignee == null || assignee.isBlank()) return; // Clearing an owner is a separate authorized command.
        String tenant = TenantContext.require();
        String target = assignee.trim();
        Operator member = tenants.getOrDefault(tenant, Map.of()).get(target);
        if (member != null) {
            if (assignable(member)) return;
        } else {
            var identity = AuthenticatedIdentityContext.current().orElse(null);
            if (identity != null && tenant.equals(identity.tenantId()) && target.equals(identity.subject())
                    && (identity.kind() == AuthenticatedIdentity.Kind.USER || identity.kind() == AuthenticatedIdentity.Kind.DEV)
                    && ("admin".equals(identity.role()) || "analyst".equals(identity.role()))) return;
        }
        throw ApiException.badRequest("Assignee must be an enabled operator in the current tenant directory");
    }

    private static boolean assignable(Operator operator) {
        return operator.enabled() && ("admin".equals(operator.role()) || "analyst".equals(operator.role()));
    }
}
