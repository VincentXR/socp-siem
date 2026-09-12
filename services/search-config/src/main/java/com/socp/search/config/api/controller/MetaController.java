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
import com.socp.platform.error.api.ApiResult;
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
@com.socp.search.config.config.SearchRuntimeRole(
        com.socp.search.config.config.SearchRuntimeRole.Role.API)
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
    public ApiResult<List<DataSourceType>> listDataSourceTypes() {
        return ApiResult.ok(dsStore.list());
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/data-source-types")
    public ApiResult<DataSourceType> createDataSourceType(@Valid @RequestBody DataSourceTypeRequest t) {
        return ApiResult.ok(dsStore.save(t.toDomain()));
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/data-source-types/{id}")
    public ApiResult<DataSourceType> updateDataSourceType(@PathVariable String id, @Valid @RequestBody DataSourceTypeRequest body) {
        DataSourceType existing = dsStore.list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "data source type not found"));
        if (!java.util.Objects.equals(existing.code(), body.code())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Existing identifiers and field types cannot be changed");
        }
        return ApiResult.ok(dsStore.save(new DataSourceType(id, body.code(), body.name(), body.description(), body.enabled(), existing.createdAt())));
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/data-source-types/{id}")
    public ApiResult<Map<String, Object>> deleteDataSourceType(@PathVariable String id) {
        return ApiResult.ok(Map.of("removed", dsStore.delete(id)));
    }

    // ---------- 日志类别 ----------

    @GetMapping("/categories")
    public ApiResult<List<LogCategory>> listCategories() {
        return ApiResult.ok(catStore.list());
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/categories")
    public ApiResult<LogCategory> createCategory(@Valid @RequestBody LogCategoryRequest c) {
        return ApiResult.ok(catStore.save(c.toDomain()));
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/categories/{id}")
    public ApiResult<LogCategory> updateCategory(@PathVariable String id, @Valid @RequestBody LogCategoryRequest body) {
        LogCategory existing = catStore.list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "log category not found"));
        if (!java.util.Objects.equals(existing.code(), body.code())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Existing identifiers and field types cannot be changed");
        }
        return ApiResult.ok(catStore.save(new LogCategory(id, body.code(), body.name(), body.description(), body.defaultSeverity(), body.enabled(), existing.createdAt())));
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/categories/{id}")
    public ApiResult<Map<String, Object>> deleteCategory(@PathVariable String id) {
        return ApiResult.ok(Map.of("removed", catStore.delete(id)));
    }

    // ---------- 字段字典 ----------

    @GetMapping("/fields")
    public ApiResult<List<FieldDef>> listFields() {
        return ApiResult.ok(fieldStore.list());
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/fields")
    public ApiResult<FieldDef> createField(@Valid @RequestBody FieldDefRequest f) {
        return ApiResult.ok(fieldStore.save(f.toDomain()));
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/fields/{id}")
    public ApiResult<FieldDef> updateField(@PathVariable String id, @Valid @RequestBody FieldDefRequest body) {
        FieldDef existing = fieldStore.list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "field not found"));
        if ("system".equals(existing.source()) || "system".equals(body.source())
                || !java.util.Objects.equals(existing.fieldName(), body.fieldName())
                || !java.util.Objects.equals(existing.fieldType(), body.fieldType())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Existing identifiers and field types cannot be changed");
        }
        return ApiResult.ok(fieldStore.save(new FieldDef(id, body.fieldName(), body.fieldLabel(), body.fieldType(), body.source(),
                body.searchable(), body.aggregatable(), body.stored(), body.description(), existing.createdAt())));
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/fields/{id}")
    public ApiResult<Map<String, Object>> deleteField(@PathVariable String id) {
        FieldDef existing = fieldStore.list().stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        if (existing != null && "system".equals(existing.source()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "System fields are read-only");
        return ApiResult.ok(Map.of("removed", fieldStore.delete(id)));
    }

    /** Source-compatible Java entry points retained for older callers; HTTP uses the methods above. */
    public LogCategory updateLogCategory(String id, LogCategoryRequest body) {
        LogCategory existing = catStore.list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "log category not found"));
        if (!java.util.Objects.equals(existing.code(), body.code())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Existing identifiers and field types cannot be changed");
        }
        return catStore.save(new LogCategory(id, body.code(), body.name(), body.description(), body.defaultSeverity(), body.enabled(), existing.createdAt()));
    }

    public FieldDef updateFieldDef(String id, FieldDefRequest body) {
        FieldDef existing = fieldStore.list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "field not found"));
        if ("system".equals(existing.source()) || "system".equals(body.source())
                || !java.util.Objects.equals(existing.fieldName(), body.fieldName())
                || !java.util.Objects.equals(existing.fieldType(), body.fieldType())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Existing identifiers and field types cannot be changed");
        }
        return fieldStore.save(new FieldDef(id, body.fieldName(), body.fieldLabel(), body.fieldType(), body.source(),
                body.searchable(), body.aggregatable(), body.stored(), body.description(), existing.createdAt()));
    }

}
