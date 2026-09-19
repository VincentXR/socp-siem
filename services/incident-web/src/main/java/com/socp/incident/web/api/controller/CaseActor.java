package com.socp.incident.web.api.controller;

import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;

/**
 * Resolves the authoritative author of a case note from the authenticated
 * principal rather than trusting the request parameter. A human (USER/DEV) can
 * never forge the recorded author, so the supplied value is ignored in favor of
 * the token subject. Machine callers (SERVICE/COLLECTOR/METRICS) legitimately
 * act on behalf of another party, so their delegated name is preserved; the
 * independent {@code @AuditOperation} entry records the calling service identity
 * as the true operator, which the timeline note alone cannot provide.
 */
final class CaseActor {

    private CaseActor() {
    }

    /**
     * @param delegate the caller-supplied author, used only for machine
     *                 identities or when no trusted principal is bound to the
     *                 request (for example, direct service-to-service calls in
     *                 tests)
     */
    static String resolve(String delegate) {
        String trimmed = delegate == null || delegate.isBlank() ? null : delegate.trim();
        return AuthenticatedIdentityContext.current()
                .map(identity -> identity.kind() == AuthenticatedIdentity.Kind.USER
                        || identity.kind() == AuthenticatedIdentity.Kind.DEV
                        ? identity.subject()
                        : (trimmed == null ? identity.subject() : trimmed))
                .orElseGet(() -> trimmed == null ? "operator" : trimmed);
    }
}
