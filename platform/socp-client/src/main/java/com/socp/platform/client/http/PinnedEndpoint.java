package com.socp.platform.client.http;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * Validated immutable address snapshot for one external request or bounded pagination cycle.
 * Pass this value to {@link PinnedHttpTransport}; validation alone does not bind an asynchronous
 * HTTP client's later DNS lookup. The legacy thread-local resolver scope is cleared by close().
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
