package com.socp.platform.client.http;

import com.socp.platform.client.config.SocpClientProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Shared egress policy for user-configured Notify and SOAR HTTP connectors. */
@Component
public class ExternalEndpointPolicy {

    private final SocpClientProperties properties;

    public ExternalEndpointPolicy(SocpClientProperties properties) {
        this.properties = properties;
    }

    /** Returns a rejection reason, or {@code null} when the endpoint is safe to call. */
    public String validate(String rawUrl) {
        return validate(rawUrl, properties.getExternalAllowedHosts(),
                properties.isExternalHttpsOnly(), properties.isExternalAllowPrivateNetworks());
    }

    /** Validate another configured egress class with the same trust-boundary rules. */
    public String validate(String rawUrl, List<String> allowedHosts,
                           boolean httpsOnly, boolean allowPrivateNetworks) {
        if (rawUrl == null || rawUrl.isBlank()) return "external endpoint is empty";
        final URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException invalid) {
            return "invalid external endpoint: " + invalid.getMessage();
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return "external endpoint must use HTTP or HTTPS";
        }
        if (httpsOnly && !scheme.equalsIgnoreCase("https")) {
            return "external endpoint must use HTTPS";
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            return "external endpoint must not contain userinfo or a fragment";
        }
        if (containsCredentialQuery(uri.getRawQuery())) {
            return "external endpoint must not contain credentials in the query";
        }
        String host = normalizeHost(uri.getHost());
        if (host == null) return "external endpoint host is empty or invalid";
        if (!allowed(host, allowedHosts)) return "external endpoint host is not allowlisted: " + host;

        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception resolutionFailure) {
            return "external endpoint host cannot be resolved: " + host;
        }
        if (addresses.length == 0) return "external endpoint host has no resolved addresses: " + host;
        for (InetAddress address : addresses) {
            if (isBlocked(address) && !allowPrivateNetworks) {
                return "external endpoint resolves to a private or reserved address: " + address.getHostAddress();
            }
        }
        return null;
    }

    private static boolean allowed(String host, List<String> allowedHosts) {
        return (allowedHosts == null ? List.<String>of() : allowedHosts).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(pattern -> pattern.equals(host)
                        || (pattern.startsWith("*.") && host.endsWith(pattern.substring(1))
                        && host.length() > pattern.length() - 1));
    }

    private static String normalizeHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) return null;
        String host = rawHost.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.isBlank() ? null : host;
    }

    private static boolean containsCredentialQuery(String query) {
        return query != null && query.matches(
                "(?i).*(^|&)(?:secret|token|password|authorization|api[_-]?key|credential|cookie)[^=]*=.*");
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            return a == 0 || a == 10 || a == 127
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && (b == 0 || b == 168))
                    || (a == 198 && (b == 18 || b == 19 || b == 51))
                    || (a == 203 && b == 0 && c == 113)
                    || a >= 224;
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        boolean mapped = true;
        for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
        mapped &= bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
        if (mapped) {
            byte[] ipv4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
            try { return isBlocked(InetAddress.getByAddress(ipv4)); }
            catch (Exception ignored) { return true; }
        }
        return (first & 0xfe) == 0xfc
                || (first == 0xfe && (second & 0xc0) == 0x80)
                || first == 0xff;
    }
}
