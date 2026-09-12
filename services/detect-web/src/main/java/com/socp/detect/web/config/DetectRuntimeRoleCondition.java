package com.socp.detect.web.config;

import java.util.Arrays;
import java.util.Locale;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Spring condition backing {@link DetectRuntimeRole}. */
final class DetectRuntimeRoleCondition implements Condition {

    static final String PROPERTY = "socp.detect.runtime-role";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment().getProperty(PROPERTY, "all")
                .trim().toUpperCase(Locale.ROOT);
        DetectRuntimeRole.Role selected;
        try {
            selected = DetectRuntimeRole.Role.valueOf(configured);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "socp.detect.runtime-role must be one of all, api, worker", invalid);
        }
        if (selected == DetectRuntimeRole.Role.ALL) return true;

        Object attributes = metadata.getAnnotationAttributes(DetectRuntimeRole.class.getName());
        if (!(attributes instanceof java.util.Map<?, ?> values)) return false;
        Object roles = values.get("value");
        if (!(roles instanceof Object[] candidates)) return false;
        return Arrays.stream(candidates)
                .map(String::valueOf)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(value -> value.equals(selected.name()) || value.equals("ALL"));
    }
}
