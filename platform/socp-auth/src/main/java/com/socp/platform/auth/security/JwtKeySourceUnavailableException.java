package com.socp.platform.auth.security;

/** Verification could not obtain trusted keys. No identity has been authenticated. */
public class JwtKeySourceUnavailableException extends JwtValidationException {
    public JwtKeySourceUnavailableException(Throwable cause) {
        super("Authentication key service is temporarily unavailable", cause);
    }
}
