package com.socp.platform.client.http;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * 一次出站校验的结果，校验通过时解析结果已钉住到当前线程。
 *
 * <p>用法（配合 try-with-resources，把「校验 → 建连」整段夹住）：
 * <pre>{@code
 * try (PinnedEndpoint pinned = policy.validatePinned(url, ...)) {
 *     if (pinned.isRejected()) { ... 拒绝 ... }
 *     ... 在同一线程内用 java.net.http / 任意客户端发起请求 ...
 * } // close() 解除钉住
 * }</pre>
 *
 * <p>钉住期间，当前线程对该主机的任何 DNS 解析（包括 HTTP 客户端建连时的隐式解析）
 * 都只会返回这里携带的已校验地址；URL 主机名不变，TLS 的 SNI 与证书域名校验不受影响。
 */
public final class PinnedEndpoint implements AutoCloseable {

    private final String host;
    private final InetAddress[] addresses;
    private final String rejection;

    private PinnedEndpoint(String host, InetAddress[] addresses, String rejection) {
        this.host = host;
        this.addresses = addresses;
        this.rejection = rejection;
    }

    static PinnedEndpoint rejected(String rejection) {
        return new PinnedEndpoint(null, new InetAddress[0], rejection);
    }

    static PinnedEndpoint resolved(String host, InetAddress[] addresses) {
        return new PinnedEndpoint(host, addresses.clone(), null);
    }

    /** true = 被 {@link ExternalEndpointPolicy} 拒绝，未发生钉住，也没有任何网络副作用。 */
    public boolean isRejected() {
        return rejection != null;
    }

    /** 拒绝原因；校验通过时为 {@code null}。 */
    public String rejectionReason() {
        return rejection;
    }

    /** 已校验主机的归一化形式（小写、去尾部根点）；拒绝时为 {@code null}。 */
    public String host() {
        return host;
    }

    /** 校验通过的全部解析地址（副本）；拒绝时为空数组。 */
    public InetAddress[] addresses() {
        return addresses.clone();
    }

    @Override
    public void close() {
        if (host != null) {
            PinnedDnsResolverProvider.unpin(host);
        }
    }

    @Override
    public String toString() {
        return isRejected() ? "PinnedEndpoint[rejected=" + rejection + "]"
                : "PinnedEndpoint[host=" + host + ", addresses=" + Arrays.toString(addresses) + "]";
    }
}
