package com.socp.search.config.config;

import java.util.Arrays;
import java.util.Locale;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Spring condition backing {@link SearchRuntimeRole}. */
final class SearchRuntimeRoleCondition implements Condition {

    static final String PROPERTY = "socp.search.runtime-role";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment().getProperty(PROPERTY, "all")
                .trim().toUpperCase(Locale.ROOT);
        SearchRuntimeRole.Role selected;
        try {
            selected = SearchRuntimeRole.Role.valueOf(configured);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "socp.search.runtime-role must be one of all, api, worker", invalid);
        }
        if (selected == SearchRuntimeRole.Role.ALL) return true;

        Object attributes = metadata.getAnnotationAttributes(SearchRuntimeRole.class.getName());
        if (!(attributes instanceof java.util.Map<?, ?> values)) return false;
        Object roles = values.get("value");
        if (!(roles instanceof Object[] candidates)) return false;
        return Arrays.stream(candidates)
                .map(String::valueOf)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(value -> value.equals(selected.name()) || value.equals("ALL"));
    }
}
