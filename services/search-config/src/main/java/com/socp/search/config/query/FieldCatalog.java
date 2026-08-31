package com.socp.search.config.query;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Single typed catalog used by SPL semantic validation and backend adapters. */
public final class FieldCatalog {
    private static final FieldCatalog STANDARD = createStandard();
    private final Map<String, FieldDescriptor> fields;

    public FieldCatalog(Map<String, FieldDescriptor> fields) {
        this.fields = Map.copyOf(fields == null ? Map.of() : fields);
    }

    public static FieldCatalog standard() {
        return STANDARD;
    }

    /** Registered, non-dynamic fields used to validate storage mappings in CI. */
    public Collection<FieldDescriptor> descriptors() {
        return fields.values();
    }

    private static FieldCatalog createStandard() {
        Map<String, FieldDescriptor> fields = new LinkedHashMap<>();
        add(fields, keyword("eventId", "eventId", "eventId", false, true, true));
        add(fields, keyword("tenantId", "tenantId", "tenantId", false, false, true));
        add(fields, new FieldDescriptor("timestamp", "timestamp", "timestamp",
                FieldDescriptor.Type.DATE, false, true, true, false, false, true));
        add(fields, keyword("source", "source", "source.ci", true, true, true));
        add(fields, keyword("host", "host", "host.ci", true, true, true));
        add(fields, new FieldDescriptor("severity", "severity", "severity",
                FieldDescriptor.Type.SEVERITY, true, true, false, true, false, true));
        add(fields, keyword("category", "fields.category", "fields.category.ci", true, true, true));
        add(fields, new FieldDescriptor("msg", "msg", "msg.exact",
                FieldDescriptor.Type.TEXT, true, false, false, false, true, true));
        add(fields, typedDynamic("src_ip", FieldDescriptor.Type.IP));
        add(fields, typedDynamic("dst_ip", FieldDescriptor.Type.IP));
        add(fields, typedDynamic("http_status", FieldDescriptor.Type.INTEGER));
        add(fields, typedDynamic("count", FieldDescriptor.Type.INTEGER));
        add(fields, typedDynamic("bytes", FieldDescriptor.Type.INTEGER));
        return new FieldCatalog(fields);
    }

    public FieldDescriptor resolve(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
            throw new SplSemanticException("invalid query field '" + name + "'", 0);
        }
        FieldDescriptor registered = fields.get(name);
        if (registered != null) return registered;
        String path = name.startsWith("fields.") || name.startsWith("ecs.")
                ? name : "fields." + name;
        return new FieldDescriptor(name, path, path + ".keyword",
                FieldDescriptor.Type.DYNAMIC_KEYWORD, true,
                false, false, false, false, false);
    }

    private static FieldDescriptor keyword(String name, String path, String exactPath,
                                           boolean caseInsensitive, boolean sortable,
                                           boolean aggregatable) {
        return new FieldDescriptor(name, path, exactPath, FieldDescriptor.Type.KEYWORD,
                caseInsensitive, false, sortable, aggregatable, false, true);
    }

    private static FieldDescriptor typedDynamic(String name, FieldDescriptor.Type type) {
        String path = "fields." + name;
        return new FieldDescriptor(name, path, path, type, false,
                true, true, true, false, true);
    }

    private static void add(Map<String, FieldDescriptor> fields, FieldDescriptor descriptor) {
        fields.put(descriptor.name(), descriptor);
    }
}
