package com.socp.search.config.persistence.store;


import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.SinkTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直接验证 {@link TenantCatalog} 的内置模板 + 租户覆盖 + 墓碑作用域。
 * 平台内置输出目标已退出 SinkTargetStore（不再是租户可见模板），故此处以
 * TenantCatalog 原语锁定「模板停用仅对当次租户生效、且不给未知 id 写墓碑」的契约。
 */
class TenantCatalogStoreTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void templateOverridesAndUserEntriesAreTenantScoped() {
        TenantCatalog<SinkTarget> catalog = new TenantCatalog<>(SinkTarget::id);
        SinkTarget template = SinkTarget.create("platform-template", "HTTP",
                "https://tpl.example", null, true);
        catalog.registerTemplate(template);
        String templateId = template.id();

        TenantContext.set("tenant-a");
        SinkTarget custom = SinkTarget.create("tenant-a-only", "HTTP", "https://a.example", null, true);
        catalog.save(custom);
        assertTrue(catalog.delete(templateId), "租户可停用共享模板");
        assertFalse(catalog.list().stream().anyMatch(target -> templateId.equals(target.id())),
                "模板对已停用它的租户不可见");
        assertTrue(catalog.list().stream().anyMatch(target -> custom.id().equals(target.id())),
                "本租户覆盖项对自身可见");

        TenantContext.set("tenant-b");
        assertTrue(catalog.list().stream().anyMatch(target -> templateId.equals(target.id())),
                "另一租户的停用不得影响本租户");
        assertFalse(catalog.list().stream().anyMatch(target -> custom.id().equals(target.id())),
                "租户覆盖项不得跨租户可见");

        // 未知 id 不再写墓碑：重启后无法回收，只会堆积无效行。
        assertFalse(catalog.delete("never-registered"));
    }
}
