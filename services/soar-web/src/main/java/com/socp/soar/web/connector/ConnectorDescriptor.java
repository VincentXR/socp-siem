package com.socp.soar.web.connector;

import java.util.List;

/** Versioned, read-only capability metadata exposed to the editor. */
public record ConnectorDescriptor(String id, int majorVersion, String displayName,
                                  boolean production, List<ActionDescriptor> actions) {
    public ConnectorDescriptor {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
