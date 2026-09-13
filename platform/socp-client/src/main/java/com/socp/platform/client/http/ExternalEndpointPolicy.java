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
        return validateAndResolve(rawUrl, allowedHosts, httpsOnly, allowPrivateNetworks).rejectionReason();
    }

    /**
     * 校验并把解析结果钉住到当前线程（默认外部出口配置）。
     *
     * <p>与 {@link #validate(String)} 相同的规则；通过后，直到 {@link PinnedEndpoint#close()}
     * 为止，当前线程对该主机的任何 DNS 解析——包括 {@code java.net.http.HttpClient}
     * 建连时的隐式解析——都只会返回已校验的地址，消除 validate-then-connect 的
     * DNS 重绑定（TOCTOU）窗口。URL 保持原主机名，SNI 与证书域名校验不受影响。
     */
    public PinnedEndpoint validatePinned(String rawUrl) {
        return validatePinned(rawUrl, properties.getExternalAllowedHosts(),
                properties.isExternalHttpsOnly(), properties.isExternalAllowPrivateNetworks());
    }

    /** Validate another configured egress class and pin the validated resolution until closed. */
    public PinnedEndpoint validatePinned(String rawUrl, List<String> allowedHosts,
                                         boolean httpsOnly, boolean allowPrivateNetworks) {
        PinnedEndpoint endpoint = validateAndResolve(rawUrl, allowedHosts, httpsOnly, allowPrivateNetworks);
        if (!endpoint.isRejected()) {
            PinnedDnsResolverProvider.pin(endpoint.host(), endpoint.addresses());
        }
        return endpoint;
    }

    private PinnedEndpoint validateAndResolve(String rawUrl, List<String> allowedHosts,
                                              boolean httpsOnly, boolean allowPrivateNetworks) {
        if (rawUrl == null || rawUrl.isBlank()) return PinnedEndpoint.rejected("external endpoint is empty");
        final URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException invalid) {
            return PinnedEndpoint.rejected("invalid external endpoint: " + invalid.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return PinnedEndpoint.rejected("external endpoint must use HTTP or HTTPS");
        }
        if (httpsOnly && !scheme.equalsIgnoreCase("https")) {
            return PinnedEndpoint.rejected("external endpoint must use HTTPS");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            return PinnedEndpoint.rejected("external endpoint must not contain userinfo or a fragment");
        }
        if (containsCredentialQuery(uri.getRawQuery())) {
            return PinnedEndpoint.rejected("external endpoint must not contain credentials in the query");
        }
        String host = normalizeHost(uri.getHost());
        if (host == null) return PinnedEndpoint.rejected("external endpoint host is empty or invalid");
        if (!allowed(host, allowedHosts)) {
            return PinnedEndpoint.rejected("external endpoint host is not allowlisted: " + host);
        }

        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception resolutionFailure) {
            return PinnedEndpoint.rejected("external endpoint host cannot be resolved: " + host);
        }
        if (addresses.length == 0) {
            return PinnedEndpoint.rejected("external endpoint host has no resolved addresses: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address) && !allowPrivateNetworks) {
                return PinnedEndpoint.rejected(
                        "external endpoint resolves to a private or reserved address: " + address.getHostAddress());
            }
        }
        return PinnedEndpoint.resolved(host, addresses);
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
                    || (a == 192 && b == 88 && c == 99)
                    || (a == 198 && (b == 18 || b == 19 || b == 51))
                    || (a == 203 && b == 0 && c == 113)
                    || a >= 224;
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        // IPv4-mapped (::ffff:0:0/96) 与 IPv4-compatible (::/96) 都在低 32 位嵌入 IPv4，
        // 内核可能按 IPv4 语义处理它们，必须按内嵌 IPv4 的保留段规则拦截
        boolean zeroPrefix = true;
        for (int i = 0; i < 10; i++) zeroPrefix &= bytes[i] == 0;
        if (zeroPrefix && bytes[10] == bytes[11]
                && (bytes[10] == (byte) 0xff || bytes[10] == 0)) {
            byte[] ipv4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
            try { return isBlocked(InetAddress.getByAddress(ipv4)); }
            catch (Exception ignored) { return true; }
        }
        // 过渡/隧道机制同样可以到达 IPv4 内网：6to4 (2002::/16) 直接内嵌 IPv4，
        // Teredo (2001:0::/32) 与 NAT64 (64:ff9b::/96、64:ff9b:1::/48) 经隧道/转换网关转发
        if (first == 0x20 && second == 0x02) {
            byte[] ipv4 = new byte[] {bytes[2], bytes[3], bytes[4], bytes[5]};
            try { return isBlocked(InetAddress.getByAddress(ipv4)); }
            catch (Exception ignored) { return true; }
        }
        if (first == 0x20 && second == 0x01 && bytes[2] == 0 && bytes[3] == 0) return true;
        if (first == 0x00 && second == 0x64 && bytes[2] == (byte) 0xff && bytes[3] == (byte) 0x9b) return true;
        return (first & 0xfe) == 0xfc
                || (first == 0xfe && (second & 0xc0) == 0x80)
                || first == 0xff;
    }
}
