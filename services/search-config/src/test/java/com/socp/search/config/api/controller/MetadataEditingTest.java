package com.socp.search.config.api.controller;

import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.api.request.DataSourceTypeRequest;
import com.socp.search.config.api.request.FieldDefRequest;
import com.socp.search.config.api.request.LogCategoryRequest;
import com.socp.search.config.persistence.store.DataSourceTypeStore;
import com.socp.search.config.persistence.store.FieldDefStore;
import com.socp.search.config.persistence.store.LogCategoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.socp.platform.auth.security.IngestBodyLimitAdvice;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataEditingTest {
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void rawMetadataBodyIsRejectedBeforeStoreAccess() throws Exception {
        var types = mock(DataSourceTypeStore.class);
        var categories = mock(LogCategoryStore.class);
        var fields = mock(FieldDefStore.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MetaController(types, categories, fields))
                .setControllerAdvice(new IngestBodyLimitAdvice(new MockEnvironment())).build();
        mvc.perform(post("/api/v1/meta/data-source-types")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"code\":\"C\",\"name\":\"N\"}" + " ".repeat(17_000)))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(types, categories, fields);
    }

    @Test void allThreeCatalogsEnforceTheirTenantLimits() {
        TenantContext.set("bounded-tenant");
        var types = new DataSourceTypeStore();
        var categories = new LogCategoryStore();
        var fields = new FieldDefStore();
        for (int index = 0; index < 119; index++) {
            types.save(com.socp.search.config.domain.DataSourceType.create("type-" + index, "name", "", true));
        }
        for (int index = 0; index < 120; index++) {
            categories.save(com.socp.search.config.domain.LogCategory.create("cat-" + index, "name", "", "LOW", true));
        }
        for (int index = 0; index < 1008; index++) {
            fields.save(com.socp.search.config.domain.FieldDef.create("field_" + index, "name", "string",
                    "custom", true, true, true, ""));
        }
        assertThat(types.list()).hasSize(128);
        assertThat(categories.list()).hasSize(128);
        assertThat(fields.list()).hasSize(1024);
        assertThatThrownBy(() -> types.save(com.socp.search.config.domain.DataSourceType.create("extra", "name", "", true)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(400);
        assertThatThrownBy(() -> categories.save(com.socp.search.config.domain.LogCategory.create("extra", "name", "", "LOW", true)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(400);
        assertThatThrownBy(() -> fields.save(com.socp.search.config.domain.FieldDef.create("extra_field", "name", "string", "custom", true, true, true, "")))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(400);
    }

    @Test void metadataKeepsStableIdsAndRejectsDuplicateOrInvalidBusinessKeys() {
        TenantContext.set("tenant-a");
        var types = new DataSourceTypeStore();
        var categories = new LogCategoryStore();
        var fields = new FieldDefStore();
        var controller = new MetaController(types, categories, fields);

        assertThat(new DataSourceTypeStore().list().stream().map(item -> item.id()).toList())
                .containsExactlyElementsOf(types.list().stream().map(item -> item.id()).toList());
        assertThat(new LogCategoryStore().list().stream().map(item -> item.id()).toList())
                .containsExactlyElementsOf(categories.list().stream().map(item -> item.id()).toList());
        assertThat(new FieldDefStore().list().stream().map(item -> item.id()).toList())
                .containsExactlyElementsOf(fields.list().stream().map(item -> item.id()).toList());

        var type = controller.createDataSourceType(new DataSourceTypeRequest("CUSTOM_TYPE", "Original", "", true)).data();
        var category = controller.createCategory(new LogCategoryRequest("CUSTOM_CAT", "Original", "", "HIGH", true)).data();
        var field = controller.createField(new FieldDefRequest("custom_field", "Original", "string", "custom", true, true, true, "")).data();
        assertThatThrownBy(() -> types.save(type))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(409);
        assertThatThrownBy(() -> controller.createDataSourceType(new DataSourceTypeRequest("custom_type", "Duplicate", "", true)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(409);
        assertThatThrownBy(() -> controller.createCategory(new LogCategoryRequest("custom_cat", "Duplicate", "", "LOW", true)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(409);
        assertThatThrownBy(() -> controller.createField(new FieldDefRequest("custom_field", "Duplicate", "string", "custom", true, true, true, "")))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(409);
        assertThatThrownBy(() -> controller.createField(new FieldDefRequest("bad_field", "Bad", "string", "system", true, true, true, "")))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(400);
        assertThatThrownBy(() -> controller.createField(new FieldDefRequest("bad_field", "Bad", "unknown", "custom", true, true, true, "")))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(400);

        var editedType = controller.updateDataSourceType(type.id(), new DataSourceTypeRequest("CUSTOM_TYPE", "Updated", "", false)).data();
        assertThat(editedType.id()).isEqualTo(type.id());
        assertThat(editedType.createdAt()).isEqualTo(type.createdAt());
        assertThat(editedType.enabled()).isFalse();
        assertThat(controller.updateLogCategory(category.id(), new LogCategoryRequest("CUSTOM_CAT", "Updated", "", "LOW", false)).id())
                .isEqualTo(category.id());
        assertThat(controller.updateFieldDef(field.id(), new FieldDefRequest("custom_field", "Updated", "string", "parse", true, false, true, "")).source())
                .isEqualTo("parse");
        assertThatThrownBy(() -> controller.updateDataSourceType(type.id(), new DataSourceTypeRequest("RENAMED", "Bad", "", true)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(409);
        assertThatThrownBy(() -> controller.updateField(field.id(), new FieldDefRequest("custom_field", "Bad", "int", "custom", true, true, true, "")))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(409);

        var system = fields.list().stream().filter(item -> "system".equals(item.source())).findFirst().orElseThrow();
        assertThatThrownBy(() -> controller.updateField(system.id(), new FieldDefRequest(system.fieldName(), "Bad", system.fieldType(), "system", true, true, true, "")))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(409);
        assertThatThrownBy(() -> controller.deleteField(system.id()))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(409);
        assertThat(controller.deleteDataSourceType(type.id()).data()).containsEntry("removed", true);
        assertThat(controller.deleteCategory(category.id()).data()).containsEntry("removed", true);
        assertThat(controller.deleteField(field.id()).data()).containsEntry("removed", true);
        assertThatThrownBy(() -> controller.updateFieldDef(field.id(), new FieldDefRequest("custom_field", "Late", "string", "custom", true, true, true, "")))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getCode()).isEqualTo(404);
        TenantContext.set("tenant-b");
        assertThat(controller.listDataSourceTypes().data()).noneMatch(item -> item.id().equals(type.id()));
        assertThat(controller.listCategories().data()).noneMatch(item -> item.id().equals(category.id()));
        assertThat(controller.listFields().data()).noneMatch(item -> item.id().equals(field.id()));
    }
}
