package com.socp.search.config.api.controller;


import com.socp.search.config.domain.DataSourceType;
import com.socp.search.config.domain.FieldDef;
import com.socp.search.config.domain.LogCategory;
import com.socp.search.config.api.request.DataSourceTypeRequest;
import com.socp.search.config.api.request.FieldDefRequest;
import com.socp.search.config.api.request.LogCategoryRequest;
import com.socp.search.config.persistence.store.DataSourceTypeStore;
import com.socp.search.config.persistence.store.FieldDefStore;
import com.socp.search.config.persistence.store.LogCategoryStore;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.security.RequireRole;

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
    @PutMapping("/data-source-types/{id}")
    public DataSourceType updateDataSourceType(@PathVariable String id, @Valid @RequestBody DataSourceTypeRequest body) {
        DataSourceType existing = dsStore.get(id);
        if (existing == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "data source type not found");
        return dsStore.save(new DataSourceType(id, body.code(), body.name(), body.description(), body.enabled(), existing.createdAt()));
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
    @PutMapping("/categories/{id}")
    public LogCategory updateCategory(@PathVariable String id, @Valid @RequestBody LogCategoryRequest body) {
        LogCategory existing = catStore.get(id);
        if (existing == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "log category not found");
        return catStore.save(new LogCategory(id, body.code(), body.name(), body.description(), body.defaultSeverity(), body.enabled(), existing.createdAt()));
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
    @PutMapping("/fields/{id}")
    public FieldDef updateField(@PathVariable String id, @Valid @RequestBody FieldDefRequest body) {
        FieldDef existing = fieldStore.get(id);
        if (existing == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "field not found");
        return fieldStore.save(new FieldDef(id, body.fieldName(), body.fieldLabel(), body.fieldType(), body.source(),
                body.searchable(), body.aggregatable(), body.stored(), body.description(), existing.createdAt()));
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/fields/{id}")
    public Map<String, Object> deleteField(@PathVariable String id) {
        FieldDef existing = fieldStore.list().stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        if (existing != null && "system".equals(existing.source()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "System fields are read-only");
        return Map.of("removed", fieldStore.delete(id));
    }
    @RequireRole({"admin", "analyst"})
    @org.springframework.web.bind.annotation.PutMapping("/data-source-types/{id}")
    public DataSourceType updateDataSourceType(@PathVariable String id, @Valid @RequestBody DataSourceTypeRequest body) {
        DataSourceType existing = dsStore.list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        if (!existing.code().equals(body.code())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Existing identifiers and field types cannot be changed");
        return dsStore.save(new DataSourceType(id, body.code(), body.name(), body.description(), body.enabled(), existing.createdAt()));
    }

    @RequireRole({"admin", "analyst"})
    @org.springframework.web.bind.annotation.PutMapping("/categories/{id}")
    public LogCategory updateLogCategory(@PathVariable String id, @Valid @RequestBody LogCategoryRequest body) {
        LogCategory existing = catStore.list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        if (!existing.code().equals(body.code())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Existing identifiers and field types cannot be changed");
        return catStore.save(new LogCategory(id, body.code(), body.name(), body.description(), body.defaultSeverity(), body.enabled(), existing.createdAt()));
    }

    @RequireRole({"admin", "analyst"})
    @org.springframework.web.bind.annotation.PutMapping("/fields/{id}")
    public FieldDef updateFieldDef(@PathVariable String id, @Valid @RequestBody FieldDefRequest body) {
        FieldDef existing = fieldStore.list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        if ("system".equals(existing.source()) || "system".equals(body.source()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "System fields are read-only");
        if (!existing.fieldName().equals(body.fieldName()) || !existing.fieldType().equals(body.fieldType())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Existing identifiers and field types cannot be changed");
        return fieldStore.save(new FieldDef(id, body.fieldName(), body.fieldLabel(), body.fieldType(), body.source(), body.searchable(), body.aggregatable(), body.stored(), body.description(), existing.createdAt()));
    }

}
