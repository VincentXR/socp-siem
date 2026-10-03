package com.socp.soar.web.connector;

import java.util.Optional;

/** Secret reference SPI. Implementations return values only inside an Activity. */
public interface SecretResolver {
    /** Bootstrap/provider-internal resolution; tenant actions must use resolveForTenant. */
    Optional<String> resolve(String reference);

    default boolean isAuthorized(String tenantId, String reference) { return false; }

    default Optional<String> resolveForTenant(String tenantId, String reference) {
        return isAuthorized(tenantId, reference) ? resolve(reference) : Optional.empty();
    }
}
