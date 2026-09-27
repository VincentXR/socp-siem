package com.socp.platform.audit.spi;

/** Marker for a sink whose durable write must share the audited business transaction. */
public interface TransactionalAuditSink extends AuditSink {
}
