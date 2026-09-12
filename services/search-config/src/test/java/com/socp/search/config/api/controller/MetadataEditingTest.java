package com.socp.search.config.api.controller;

import com.socp.search.config.api.request.DataSourceTypeRequest;
import com.socp.search.config.api.request.FieldDefRequest;
import com.socp.search.config.api.request.LogCategoryRequest;
import com.socp.search.config.domain.DataSourceType;
import com.socp.search.config.domain.FieldDef;
import com.socp.search.config.domain.LogCategory;
import com.socp.search.config.persistence.store.DataSourceTypeStore;
import com.socp.search.config.persistence.store.FieldDefStore;
import com.socp.search.config.persistence.store.LogCategoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataEditingTest {
    @Test
    void editsKeepStableReferencesAndSystemFieldsCannotBeChanged() {
        DataSourceTypeStore types = mock(DataSourceTypeStore.class);
        LogCategoryStore categories = mock(LogCategoryStore.class);
        FieldDefStore fields = mock(FieldDefStore.class);
        MetaController controller = new MetaController(types, categories, fields);
        DataSourceType type = DataSourceType.create("syslog", "Original", "", true);
        LogCategory category = LogCategory.create("auth", "Original", "", "HIGH", true);
        FieldDef field = FieldDef.create("user", "Original", "string", "custom", true, true, true, "");
        FieldDef system = FieldDef.create("tenant_id", "Tenant", "string", "system", true, true, true, "");
        when(types.list()).thenReturn(List.of(type));
        when(categories.list()).thenReturn(List.of(category));
        when(fields.list()).thenReturn(List.of(field, system));
        when(types.save(any())).thenAnswer(call -> call.getArgument(0));
        when(categories.save(any())).thenAnswer(call -> call.getArgument(0));
        when(fields.save(any())).thenAnswer(call -> call.getArgument(0));

        DataSourceType edited = controller.updateDataSourceType(type.id(), new DataSourceTypeRequest("syslog", "Updated", "", false)).data();
        assertThat(edited.id()).isEqualTo(type.id());
        assertThat(edited.createdAt()).isEqualTo(type.createdAt());
        assertThat(edited.enabled()).isFalse();
        assertThat(controller.updateLogCategory(category.id(), new LogCategoryRequest("auth", "Updated", "", "LOW", false)).id()).isEqualTo(category.id());
        assertThat(controller.updateFieldDef(field.id(), new FieldDefRequest("user", "User name", "string", "custom", true, false, true, "")).fieldLabel()).isEqualTo("User name");
        assertThatThrownBy(() -> controller.updateDataSourceType(type.id(), new DataSourceTypeRequest("renamed", "Updated", "", true))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.updateFieldDef(field.id(), new FieldDefRequest("user", "User", "int", "custom", true, true, true, ""))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.updateFieldDef(system.id(), new FieldDefRequest("tenant_id", "Tenant", "string", "system", true, true, true, ""))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.deleteField(system.id())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.updateLogCategory("missing", new LogCategoryRequest("auth", "Updated", "", "LOW", false))).isInstanceOf(ResponseStatusException.class);
    }
}
