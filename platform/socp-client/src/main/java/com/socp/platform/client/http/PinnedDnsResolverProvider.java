package com.socp.platform.client.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * JVM 级 DNS 解析器 SPI：把「校验通过的解析结果」钉住到当前线程。
 *
 * <p><b>它解决什么</b>：出站校验（{@link ExternalEndpointPolicy}）是 validate-then-connect
 * 模式——校验时解析一次 DNS，真正建连时 {@code java.net.http.HttpClient} 会再次解析。
 * 两次解析之间攻击者（掌控权威 DNS 的一方）可以把域名从公网地址改到 169.254.169.254
 * 或内网地址，校验就形同虚设。这里通过 {@link java.net.spi.InetAddressResolverProvider}
 * 注册一个全局解析器：当线程持有钉住记录时，对该主机的任何解析（包括建连时的隐式解析）
 * 都只会返回已校验的地址，从而消除 TOCTOU 窗口。
 *
 * <p><b>为什么不在建连层换 IP</b>：把 URL 里的主机名替换成裸 IP 会让 TLS 证书校验
 * 退化为对 IP 的校验（绝大多数证书没有 IP SAN），等于自废 HostNameVerifier。
 * 钉住发生在 DNS 层，URL 原样保留——SNI、Host 头、证书域名校验全部不受影响，
 * 且对 http / https 两种 scheme 一致生效。未持有钉住记录的线程完全走原有解析路径。
 *
 * <p>钉住记录按线程隔离（ThreadLocal），由 {@code PinnedEndpoint.close()}（配合
 * try-with-resources）负责解除，不会跨请求泄漏。
 */
public final class PinnedDnsResolverProvider extends InetAddressResolverProvider {

    private static final ThreadLocal<Map<String, InetAddress[]>> PINNED = new ThreadLocal<>();

    /** 把 host 的解析结果钉住到当前线程（覆盖同名旧记录）。 */
    public static void pin(String host, InetAddress[] addresses) {
        String key = normalize(host);
        if (key == null || addresses == null || addresses.length == 0) return;
        Map<String, InetAddress[]> current = PINNED.get();
        Map<String, InetAddress[]> next = new HashMap<>(current == null ? Map.of() : current);
        next.put(key, addresses.clone());
        PINNED.set(next);
    }

    /** 解除 host 的钉住记录；未钉住时为空操作。 */
    public static void unpin(String host) {
        String key = normalize(host);
        Map<String, InetAddress[]> current = key == null ? null : PINNED.get();
        if (current == null || !current.containsKey(key)) return;
        Map<String, InetAddress[]> next = new HashMap<>(current);
        next.remove(key);
        if (next.isEmpty()) {
            PINNED.remove();
        } else {
            PINNED.set(next);
        }
    }

    /** 仅用于测试与诊断：host 当前是否被本线程钉住。 */
    public static boolean isPinned(String host) {
        String key = normalize(host);
        Map<String, InetAddress[]> pinned = PINNED.get();
        return key != null && pinned != null && pinned.containsKey(key);
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        return resolver(configuration.builtinResolver());
    }

    /** Package-private seam keeps resolver behavior deterministic in unit tests. */
    static InetAddressResolver resolver(InetAddressResolver fallback) {
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                Map<String, InetAddress[]> pinned = PINNED.get();
                if (pinned != null) {
                    InetAddress[] addresses = pinned.get(normalize(host));
                    if (addresses != null) return Stream.of(addresses);
                }
                return fallback.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                return fallback.lookupByAddress(address);
            }
        };
    }

    @Override
    public String name() {
        return "socp-pinned-dns";
    }

    /** 与 {@link ExternalEndpointPolicy} 的主机归一化保持一致：小写、去尾部根点。 */
    private static String normalize(String host) {
        if (host == null || host.isBlank()) return null;
        String normalized = host.toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isBlank() ? null : normalized;
    }
}
