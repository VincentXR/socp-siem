package com.socp.platform.tenant.persistence;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;

/**
 * Repository contract for tenant-owned data.
 *
 * <p>Generic reads and deletes are deliberately fail-closed.  A tenant
 * repository must expose a method whose name contains the tenant boundary
 * (for example {@code findByTenantIdAndId}) or an explicitly documented
 * system-scope query for a background worker.  This keeps a convenient
 * {@code findById} from silently becoming a cross-tenant data leak.</p>
 */
@NoRepositoryBean
public interface TenantScopedRepository<T, ID> extends JpaRepository<T, ID> {

    List<T> findByTenantId(String tenantId);

    @Override
    default Optional<T> findById(ID id) {
        throw violation("findById");
    }

    @Override
    default List<T> findAll() {
        throw violation("findAll");
    }

    @Override
    default List<T> findAll(Sort sort) {
        throw violation("findAll(Sort)");
    }

    @Override
    default List<T> findAllById(Iterable<ID> ids) {
        throw violation("findAllById");
    }

    @Override
    default boolean existsById(ID id) {
        throw violation("existsById");
    }

    @Override
    default long count() {
        throw violation("count");
    }

    @Override
    default void deleteById(ID id) {
        throw violation("deleteById");
    }

    @Override
    default void deleteAllById(Iterable<? extends ID> ids) {
        throw violation("deleteAllById");
    }

    @Override
    default void deleteAll() {
        throw violation("deleteAll");
    }

    @Override
    default void deleteAll(Iterable<? extends T> entities) {
        throw violation("deleteAll(entities)");
    }

    @Override
    default void deleteAllInBatch() {
        throw violation("deleteAllInBatch");
    }

    @Override
    default void deleteAllInBatch(Iterable<T> entities) {
        throw violation("deleteAllInBatch(entities)");
    }

    @Override
    default void deleteAllByIdInBatch(Iterable<ID> ids) {
        throw violation("deleteAllByIdInBatch");
    }

    @Override
    default T getOne(ID id) {
        throw violation("getOne");
    }

    @Override
    default T getById(ID id) {
        throw violation("getById");
    }

    @Override
    default T getReferenceById(ID id) {
        throw violation("getReferenceById");
    }

    @Override
    default <S extends T> List<S> findAll(Example<S> example) {
        throw violation("findAll(Example)");
    }

    @Override
    default <S extends T> List<S> findAll(Example<S> example, Sort sort) {
        throw violation("findAll(Example, Sort)");
    }

    @Override
    default <S extends T> Optional<S> findOne(Example<S> example) {
        throw violation("findOne(Example)");
    }

    @Override
    default <S extends T> long count(Example<S> example) {
        throw violation("count(Example)");
    }

    @Override
    default <S extends T> boolean exists(Example<S> example) {
        throw violation("exists(Example)");
    }

    private static UnsupportedOperationException violation(String method) {
        return new UnsupportedOperationException(
                "Tenant-scoped repository method requires an explicit tenant boundary: " + method);
    }
}
