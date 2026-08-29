package com.socp.platform.tenant.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class TenantScopedRepositoryTest {

    @SuppressWarnings("unchecked")
    private TenantScopedRepository<Object, String> repository() {
        return mock(TenantScopedRepository.class, CALLS_REAL_METHODS);
    }

    @Test
    void genericJpaReadsAndDeletesFailClosed() {
        TenantScopedRepository<Object, String> repository = repository();
        Example<Object> example = Example.of(new Object());

        assertThatThrownBy(() -> repository.findById("id")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(repository::findAll).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.findAll(Sort.unsorted()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.findAllById(List.of("id")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.existsById("id"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(repository::count).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.deleteById("id"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.deleteAllById(List.of("id")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(repository::deleteAll).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.deleteAll(List.of(new Object())))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(repository::deleteAllInBatch).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.deleteAllInBatch(List.of(new Object())))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.deleteAllByIdInBatch(List.of("id")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.getOne("id"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.getById("id"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.getReferenceById("id"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.findAll(example))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.findAll(example, Sort.unsorted()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.findOne(example))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.count(example))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.exists(example))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
