package com.socp.alert.api.controller;

import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;

/**
 * Resolves the authoritative actor of a disposition write from the authenticated
 * principal rather than trusting the request body. A human (USER/DEV) can never
 * forge the recorded author, so the body value is ignored in favor of the token
 * subject. Machine callers (SERVICE/COLLECTOR/METRICS) legitimately act on behalf
 * of another party, so their delegated name is preserved; the independent
 * {@code @AuditOperation} entry records the calling service identity as the true
 * operator, which is what the disposition note alone cannot provide.
 */
final class DispositionActor {

    private DispositionActor() {
    }

    /**
     * @param delegate the caller-supplied author/actor, used only for machine
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
