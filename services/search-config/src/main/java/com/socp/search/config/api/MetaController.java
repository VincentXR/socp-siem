package com.socp.search.config.api;

import com.socp.search.config.domain.DataSourceType;
import com.socp.search.config.domain.FieldDef;
import com.socp.search.config.domain.LogCategory;
import com.socp.search.config.store.DataSourceTypeStore;
import com.socp.search.config.store.FieldDefStore;
import com.socp.search.config.store.LogCategoryStore;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.RequireRole;

/**
 * 元数据管理 API：数据源分类 / 日志类别 / 字段字典。
 * 统一前缀 /api/v1/meta，供控制台「元数据」模块使用。
 */
@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

    private final DataSourceTypeStore dsStore;
    private final LogCategoryStore catStore;
    private final FieldDefStore fieldStore;

    public MetaController(DataSourceTypeStore dsStore, LogCategoryStore catStore, FieldDefStore fieldStore) {
        this.dsStore = dsStore;
        this.catStore = catStore;
        this.fieldStore = fieldStore;
    }

    // ---------- 数据源分类 ----------

    @GetMapping("/data-source-types")
    public List<DataSourceType> listDataSourceTypes() {
        return dsStore.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/data-source-types")
    public DataSourceType createDataSourceType(@Valid @RequestBody DataSourceTypeRequest t) {
        return dsStore.save(t.toDomain());
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/data-source-types/{id}")
    public Map<String, Object> deleteDataSourceType(@PathVariable String id) {
        return Map.of("removed", dsStore.delete(id));
    }

    // ---------- 日志类别 ----------

    @GetMapping("/categories")
    public List<LogCategory> listCategories() {
        return catStore.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/categories")
    public LogCategory createCategory(@Valid @RequestBody LogCategoryRequest c) {
        return catStore.save(c.toDomain());
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/categories/{id}")
    public Map<String, Object> deleteCategory(@PathVariable String id) {
        return Map.of("removed", catStore.delete(id));
    }

    // ---------- 字段字典 ----------

    @GetMapping("/fields")
    public List<FieldDef> listFields() {
        return fieldStore.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/fields")
    public FieldDef createField(@Valid @RequestBody FieldDefRequest f) {
        return fieldStore.save(f.toDomain());
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/fields/{id}")
    public Map<String, Object> deleteField(@PathVariable String id) {
        return Map.of("removed", fieldStore.delete(id));
    }
}
