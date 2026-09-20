package com.socp.platform.auth.security;

import java.time.Instant;

/**
 * Per-subject session termination deadline ("auth epoch").
 *
 * <p>Logout of a single browser session is the gateway's control: it publishes the
 * token id to a shared deny list. What a deny list cannot express is the operation an
 * analyst actually needs when an account is compromised or shared - invalidate every
 * session of a subject at once, including the ones whose token ids nobody knows. A
 * subject-scoped deadline does that without a session inventory: any token whose
 * {@code iat} precedes the deadline stops authenticating at <em>every</em> service
 * boundary, not only at the gateway.</p>
 *
 * <p>The comparison needs {@code iat}; a token without it cannot prove it outlived the
 * deadline, so it is rejected while a deadline is active rather than waved through.
 * Enforcing the verdict is the job of {@link AuthInterceptor}; writing one is the job
 * of the session administration endpoint in the owning service.</p>
 */
public interface SessionEpochStore {

    /** Fail-closed result of the read: a store that cannot be answered is not "valid". */
    enum Verdict {
        VALID,
        REVOKED,
        UNAVAILABLE
    }

    /**
     * @param subject  the authenticated JWT subject
     * @param issuedAt the token's {@code iat}, or {@code null} when it carries none
     */
    Verdict evaluate(String subject, Instant issuedAt);

    /**
     * Raises the subject's deadline so every session issued earlier is rejected.
     * Concurrent invalidations take the later deadline, never an older one.
     *
     * @return the effective deadline
     */
    Instant invalidate(String subject, Instant notBefore);
}
