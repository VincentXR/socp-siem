package com.socp.platform.client.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** DNS 钉住解析器的行为契约：钉住即生效、归一化一致、按线程隔离。 */
class PinnedDnsResolverProviderTest {

    // 每个测试使用独立主机名，避免 InetAddress 负缓存跨测试串扰
    private static final String PINNED_HOST = "socp-pinned.test.invalid";
    private static final String UNPINNED_HOST = "socp-unpinned.test.invalid";
    private static final String SCOPED_HOST = "socp-scoped.test.invalid";

    @AfterEach
    void unpinAll() {
        PinnedDnsResolverProvider.unpin(PINNED_HOST);
        PinnedDnsResolverProvider.unpin(UNPINNED_HOST);
        PinnedDnsResolverProvider.unpin(SCOPED_HOST);
    }

    @Test
    void pinnedHostsResolveToThePinnedAddressesWithoutRealDns() throws Exception {
        InetAddress pinnedAddress = pinnedAddressFor(PINNED_HOST);
        PinnedDnsResolverProvider.pin(PINNED_HOST, new InetAddress[] {pinnedAddress});

        // .invalid TLD 在真实 DNS 中不存在：能解析出来说明走的是钉住记录
        InetAddress resolved = InetAddress.getByName(PINNED_HOST);
        assertThat(resolved.getAddress()).isEqualTo(pinnedAddress.getAddress());

        // 主机名大小写与尾部根点都归一到同一个 key
        InetAddress normalized = InetAddress.getByName(PINNED_HOST.toUpperCase(Locale.ROOT) + ".");
        assertThat(normalized.getAddress()).isEqualTo(pinnedAddress.getAddress());
    }

    @Test
    void unpinRemovesThePinnedRecord() {
        PinnedDnsResolverProvider.pin(UNPINNED_HOST, new InetAddress[] {pinnedAddressFor(UNPINNED_HOST)});
        assertThat(PinnedDnsResolverProvider.isPinned(UNPINNED_HOST)).isTrue();

        PinnedDnsResolverProvider.unpin(UNPINNED_HOST);

        assertThat(PinnedDnsResolverProvider.isPinned(UNPINNED_HOST)).isFalse();
        // 重复 unpin 为空操作，不抛异常
        PinnedDnsResolverProvider.unpin(UNPINNED_HOST);
    }

    @Test
    void pinningIsScopedToThePinningThread() throws Exception {
        InetAddress pinnedAddress = pinnedAddressFor(SCOPED_HOST);
        PinnedDnsResolverProvider.pin(SCOPED_HOST, new InetAddress[] {pinnedAddress});
        try {
            AtomicReference<Object> resolvedElsewhere = new AtomicReference<>();
            Thread otherThread = new Thread(() -> {
                try {
                    resolvedElsewhere.set(InetAddress.getByName(SCOPED_HOST));
                } catch (Exception unknownOnOtherThread) {
                    resolvedElsewhere.set(unknownOnOtherThread);
                }
            });
            otherThread.start();
            otherThread.join(10_000);

            // 关键安全属性：其他线程拿不到钉住地址（要么解析失败，要么是真实解析结果）
            assertThat(resolvedElsewhere.get()).isNotEqualTo(pinnedAddress);
        } finally {
            PinnedDnsResolverProvider.unpin(SCOPED_HOST);
        }
    }

    private static InetAddress pinnedAddressFor(String host) {
        try {
            // TEST-NET-2 段，不可能出现在真实路由里
            return InetAddress.getByAddress(host, new byte[] {(byte) 198, 18, 0, 1});
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
