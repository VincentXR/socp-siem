package com.socp.soar.web.connector;

import java.util.Optional;

/** Controlled runtime contract for one SOAR connector implementation. */
public interface SoarConnector {
    ConnectorDescriptor descriptor();
    ConnectionTestResult test(ConnectionContext connection);
    ActionResult execute(ActionRequest request);

    default Optional<ActionResult> reconcile(ActionQuery query) { return Optional.empty(); }
    default Optional<ActionResult> compensate(ActionRequest request) { return Optional.empty(); }
}
