package com.socp.soc.store;

import com.socp.soc.model.TenantInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内租户存储单测（生产替换为 PG soc.t_tenant 时应保持同样语义）。
 */
class TenantStoreTest {

    @Test
    void seedsThreeDefaultTenants() {
        TenantStore store = new TenantStore();

        List<String> codes = store.list().stream().map(TenantInfo::code).sorted().toList();

        assertEquals(3, codes.size(), "构造函数应写入 3 个种子租户");
        assertEquals(List.of("default", "infra", "soc-team"), codes);
    }

    @Test
    void saveIsUpsertKeyedById() {
        TenantStore store = new TenantStore();
        int before = store.list().size();

        TenantInfo t = TenantInfo.create("红队", "red-team");
        store.save(t);
        assertEquals(before + 1, store.list().size(), "新 id 应新增一条");

        TenantInfo renamed = new TenantInfo(t.id(), "红队(改)", t.code(), 7, 3, t.createdAt());
        store.save(renamed);

        assertEquals(before + 1, store.list().size(), "同 id 应覆盖而非新增");
        assertEquals("红队(改)", store.get(t.id()).name());
        assertEquals(7, store.get(t.id()).userCount());
    }

    @Test
    void getUnknownIdReturnsNull() {
        assertNull(new TenantStore().get("not-exists"));
    }

    @Test
    void createInitialisesCountersAndTimestamp() {
        TenantInfo t = TenantInfo.create("默认租户", "default");

        assertNotNull(t.id());
        assertNotNull(t.createdAt());
        assertEquals(0, t.userCount());
        assertEquals(0, t.alarmCount());
        assertTrue(t.id().length() >= 36, "id 应为 UUID");
    }
}
