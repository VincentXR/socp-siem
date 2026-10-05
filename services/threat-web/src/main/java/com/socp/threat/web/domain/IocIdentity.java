package com.socp.threat.web.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Business identity is tenant-scoped in PostgreSQL; the public row ID is opaque. */
public final class IocIdentity {
    private IocIdentity() { }

    public static String key(String type, String value, String source, String externalId) {
        String feed = source == null || source.isBlank() ? "manual" : source.trim();
        String external = externalId == null ? "" : externalId.trim();
        String[] parts = external.isEmpty()
                ? new String[] {"manual", feed, type.trim().toUpperCase(Locale.ROOT), value.trim().toLowerCase(Locale.ROOT)}
                : new String[] {"external", feed, external};
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
