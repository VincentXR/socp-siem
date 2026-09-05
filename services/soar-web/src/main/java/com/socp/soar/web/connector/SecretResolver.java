package com.socp.soar.web.connector;

import java.util.Optional;

/** Secret reference SPI. Implementations return values only inside an Activity. */
public interface SecretResolver {
    Optional<String> resolve(String reference);
}
