package com.socp.search.config.search;

import com.socp.search.config.store.ParseRuleStore;
import com.socp.search.config.service.ParsePreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPL 引擎单测：条件过滤 + 管道统计。
 */
class SplEngineTest {

    private final SplEngine engine = new SplEngine();
    /** 空仓储：findAll 返回空触发种子数据，save 不落库——仅测 SPL 逻辑；OpenSearch/Kafka 写器置 null 跳过 */
    private final SearchStore store = new SearchStore(new EmptyRepo(), null);

    private static final class EmptyRepo implements SearchEventRepository {
        @Override public List<SearchEventEntity> findAll() { return List.of(); }
        @Override public SearchEventEntity save(SearchEventEntity e) { return e; }
        @Override public Optional<SearchEventEntity> findById(String s) { return Optional.empty(); }
        @Override public List<SearchEventEntity> findAllById(Iterable<String> ids) { return List.of(); }
        @Override public boolean existsById(String s) { return false; }
        @Override public long count() { return 0; }
        @Override public void deleteById(String s) { }
        @Override public void delete(SearchEventEntity e) { }
        @Override public void deleteAllById(Iterable<? extends String> ids) { }
        @Override public void deleteAll(Iterable<? extends SearchEventEntity> entities) { }
        @Override public void deleteAll() { }
        @Override public <S extends SearchEventEntity> List<S> saveAll(Iterable<S> es) { return java.util.stream.StreamSupport.stream(es.spliterator(), false).toList(); }
        @Override public void flush() { }
        @Override public <S extends SearchEventEntity> S saveAndFlush(S e) { return e; }
        @Override public <S extends SearchEventEntity> List<S> saveAllAndFlush(Iterable<S> es) { return java.util.stream.StreamSupport.stream(es.spliterator(), false).toList(); }
        @Override public void deleteAllInBatch(Iterable<SearchEventEntity> es) { }
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) { }
        @Override public void deleteAllInBatch() { }
        @Override public SearchEventEntity getOne(String s) { return null; }
        @Override public SearchEventEntity getById(String s) { return null; }
        @Override public SearchEventEntity getReferenceById(String s) { return null; }
        @Override public <S extends SearchEventEntity> List<S> findAll(Example<S> ex) { return List.of(); }
        @Override public <S extends SearchEventEntity> List<S> findAll(Example<S> ex, Sort sort) { return List.of(); }
        @Override public <S extends SearchEventEntity> Optional<S> findOne(Example<S> ex) { return Optional.empty(); }
        @Override public <S extends SearchEventEntity> Page<S> findAll(Example<S> ex, Pageable p) { return null; }
        @Override public <S extends SearchEventEntity> long count(Example<S> ex) { return 0; }
        @Override public <S extends SearchEventEntity> boolean exists(Example<S> ex) { return false; }
        @Override public <S extends SearchEventEntity, R> R findBy(Example<S> ex, Function<FluentQuery.FetchableFluentQuery<S>, R> q) { return null; }
        @Override public List<SearchEventEntity> findAll(Sort sort) { return List.of(); }
        @Override public Page<SearchEventEntity> findAll(Pageable p) { return null; }
    }

    @Test
    void simpleEqFilter() {
        var r = engine.execute("source=auth", store.all());
        assertTrue(r.total() > 20, "auth 事件应有多条");
        assertTrue(r.events().stream().allMatch(e -> e.source().equals("auth")));
    }

    @Test
    void andOrAndContains() {
        var r = engine.execute("source=auth severity=HIGH", store.all());
        assertTrue(r.total() > 0);
        assertTrue(r.events().stream().allMatch(e -> e.source().equals("auth") && e.severity().equals("HIGH")));

        var r2 = engine.execute("severity=CRITICAL OR severity=HIGH", store.all());
        assertTrue(r2.total() > 0, "OR 组合应命中");

        var r3 = engine.execute("msg contains \"blocked\"", store.all());
        assertTrue(r3.total() > 0, "contains 应命中防火墙");
    }

    @Test
    void numericCompare() {
        var r = engine.execute("bytes>=1000", store.all());
        assertTrue(r.total() > 0, "数值比较应命中 Web 200 响应");
        assertTrue(r.events().stream().allMatch(e -> {
            try {
                return Double.parseDouble(e.get("bytes")) >= 1000;
            } catch (NumberFormatException ex) {
                return false;
            }
        }));
    }

    @Test
    void topAndTimechartPipes() {
        var r = engine.execute("source=firewall | top src_ip 3", store.all());
        assertEquals("top", r.stat().type());
        assertTrue(r.stat().rows().size() <= 3);
        long first = (Long) r.stat().rows().get(0).get("count");
        for (var row : r.stat().rows()) {
            assertTrue((Long) row.get("count") <= first, "top 应降序");
        }

        var t = engine.execute("severity>=HIGH | timechart", store.all());
        assertEquals("timechart", t.stat().type());
        assertTrue(t.stat().rows().size() >= 2, "跨 7 天应有多个桶");
    }
    @Test
    void preservesEventIdentityAndEcsFieldsAcrossLocalPersistence() {
        SearchEvent original = new SearchEvent("evt-42", java.time.Instant.parse("2026-08-18T10:00:00Z"),
                "auth", "host-1", "HIGH", "failed login",
                Map.of("user", "admin"), Map.of("source.ip", "10.0.0.9"));

        SearchEventEntity entity = SearchStore.toEntity(original);
        SearchEvent restored = SearchStore.fromEntity(entity);

        assertEquals("evt-42", entity.getEventId());
        assertEquals("evt-42", restored.eventId());
        assertEquals("admin", restored.fields().get("user"));
        assertEquals("10.0.0.9", restored.ecs().get("source.ip"));
    }
}
