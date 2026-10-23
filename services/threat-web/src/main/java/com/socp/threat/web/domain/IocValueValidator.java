package com.socp.threat.web.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Literal IP validation. Never resolve indicator values through DNS. */
public final class IocValueValidator {
    private IocValueValidator() { }

    public static boolean valid(String type, String value) {
        if (!"IP".equalsIgnoreCase(type)) return true;
        if (value == null || value.isBlank()) return false;
        String literal = value.trim();
        if (literal.contains(":")) {
            // A colon plus this alphabet restricts getByName to IPv6 literal
            // parsing; hostnames, brackets and interface scopes are excluded.
            if (!literal.matches("[0-9a-fA-F:.]+")) return false;
            if (literal.contains(".") && !ipv4(literal.substring(literal.lastIndexOf(':') + 1))) return false;
            try {
                InetAddress.getByName(literal);
                return true;
            } catch (UnknownHostException invalid) {
                return false;
            }
        }
        return ipv4(literal);
    }

    private static boolean ipv4(String literal) {
        String[] octets = literal.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }
}
