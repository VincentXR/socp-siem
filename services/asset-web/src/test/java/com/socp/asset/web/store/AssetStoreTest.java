package com.socp.asset.web.store;

import com.socp.asset.web.model.Asset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 资产存储单测：种子数据、幂等 upsert、删除语义（Mockito mock 仓储，不依赖真实 DB）。
 * 生产切到 PG asset.t_asset 后接口不变，这些断言应继续成立。
 */
class AssetStoreTest {

    private AssetRepository repo;
    private final List<AssetEntity> db = new ArrayList<>();
    private AssetStore store;

    @BeforeEach
    void setup() {
        repo = mock(AssetRepository.class);
        when(repo.count()).thenReturn(0L); // 触发种子
        doAnswer(inv -> { db.add(inv.getArgument(0)); return null; }).when(repo).save(any());
        doAnswer(inv -> { db.remove(inv.getArgument(0)); return null; }).when(repo).delete(any());
        when(repo.findByTenantId(any())).thenAnswer(inv ->
                db.stream().filter(e -> "default".equals(e.getTenantId())).toList());
        when(repo.findByIdAndTenantId(any(), any())).thenAnswer(inv ->
                db.stream().filter(e -> e.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByIpAndTenantId(any(), any())).thenAnswer(inv ->
                db.stream().filter(e -> e.getIp() != null && e.getIp().equals(inv.getArgument(0))).toList());
        store = new AssetStore(repo);
    }

    @Test
    void seedsFiveDemoAssets() {
        List<Asset> all = store.list();
        assertEquals(5, all.size());
        assertTrue(all.stream().anyMatch(a -> "fw-core".equals(a.name()) && "FIREWALL".equals(a.type())));
        assertTrue(all.stream().anyMatch(a -> "CRITICAL".equals(a.criticality())));
    }

    @Test
    void saveThenDeleteRoundTrip() {
        int before = store.list().size();

        Asset a = Asset.create("app01", "SERVER", "10.0.0.30", "RHEL 9", "app", "MEDIUM");
        assertEquals(a, store.save(a), "save 应原样回传，便于控制器直接返回");
        assertEquals(before + 1, store.list().size());

        assertTrue(store.delete(a.id()), "已存在的 id 删除返回 true");
        assertEquals(before, store.list().size());
        assertFalse(store.delete(a.id()), "重复删除返回 false");
    }

    @Test
    void createGeneratesIdAndTimestamp() {
        Asset a = Asset.create("db-slave", "DATABASE", "10.0.0.11", "PostgreSQL 18", "dba", "HIGH");

        assertNotNull(a.id());
        assertNotNull(a.createdAt());
        assertEquals("db-slave", a.name());
        assertEquals("HIGH", a.criticality());
    }
}
