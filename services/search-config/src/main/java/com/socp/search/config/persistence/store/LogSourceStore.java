package com.socp.search.config.persistence.store;


import com.socp.search.config.persistence.repository.LogSourceRepository;
import com.socp.search.config.persistence.entity.LogSourceEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志源存储——本地切片用 H2 文件库（重启不丢）；生产由独立 search 库 PG 承载。
 * 读出口分三层：HTTP 目录走 {@link #page}，计数走 SQL，全量 {@link #list()} 只留给
 * 启动播种与 Vector 渲染这类内部批处理。
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class LogSourceStore {

    private final LogSourceRepository repo;
    private final Map<String, AtomicLong> revisions = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public LogSourceStore(LogSourceRepository repo) {
        this.repo = repo;
    }

    public synchronized LogSource save(LogSource src) {
        repo.save(toEntity(src));
        bumpRevision();
        return src;
    }

    public Optional<LogSource> get(String id) {
        return repo.findByTenantIdAndSourceId(tenant(), id).map(LogSourceStore::fromEntity);
    }

    /** Resolve the stable tag emitted by the rendered Vector transform. */
    public Optional<LogSource> findByCollectorTag(String collectorTag) {
        if (collectorTag == null || collectorTag.isBlank()) return Optional.empty();
        for (Object[] identity : repo.findIdentityProjections(tenant())) {
            String sourceId = (String) identity[0];
            if (collectorTag.equals(LogSource.collectorTagOf(sourceId, (String) identity[1]))) {
                return get(sourceId);
            }
        }
        return Optional.empty();
    }

    /**
     * Full tenant materialisation. Internal batch use only (bootstrap seeding and Vector
     * rendering); HTTP handlers must use {@link #page(Pageable)} or the count projections.
     */
    public List<LogSource> list() {
        List<LogSource> out = new ArrayList<>();
        for (LogSourceEntity e : repo.findByTenantId(tenant())) out.add(fromEntity(e));
        return out;
    }

    /** Database-side page used by the HTTP catalogue; avoids loading every source. */
    public Page<LogSource> page(Pageable pageable) {
        return repo.findByTenantId(tenant(), pageable).map(LogSourceStore::fromEntity);
    }

    public List<LogSource> enabled() {
        List<LogSource> out = new ArrayList<>();
        for (LogSourceEntity e : repo.findByTenantIdAndEnabledTrue(tenant())) out.add(fromEntity(e));
        return out;
    }

    /** SQL-side catalogue size; never loads rows. */
    public long count() {
        return repo.countByTenantId(tenant());
    }

    /** SQL-side enabled size; never loads rows. */
    public long countEnabled() {
        return repo.countByTenantIdAndEnabledTrue(tenant());
    }

    /** Collector tags of enabled sources, resolved without JSON deserialisation. */
    public List<String> enabledCollectorTags() {
        List<String> tags = new ArrayList<>();
        for (Object[] identity : repo.findEnabledIdentityProjections(tenant())) {
            tags.add(LogSource.collectorTagOf((String) identity[0], (String) identity[1]));
        }
        return List.copyOf(tags);
    }

    public synchronized boolean delete(String id) {
        Optional<LogSourceEntity> entity = repo.findByTenantIdAndSourceId(tenant(), id);
        if (entity.isPresent()) {
            repo.delete(entity.get());
            bumpRevision();
            return true;
        }
        return false;
    }

    /**
     * Change token of one tenant's catalogue. Tokens are per tenant on purpose: a shared
     * counter let one tenant's write flush every other tenant's source/pipeline cache.
     */
    public long revision(String tenantId) {
        AtomicLong token = tenantId == null ? null : revisions.get(tenantId);
        return token == null ? 0L : token.get();
    }

    private void bumpRevision() {
        revisions.computeIfAbsent(tenant(), ignored -> new AtomicLong()).incrementAndGet();
    }

    // ---- 互转 ----

    static LogSourceEntity toEntity(LogSource s) {
        LogSourceEntity e = new LogSourceEntity();
        e.setId(s.id());
        String tenant = tenant();
        e.setTenantId(tenant);
        e.setStorageId(UUID.nameUUIDFromBytes((tenant + "|" + s.id())
                .getBytes(StandardCharsets.UTF_8)).toString());
        e.setName(s.name());
        e.setType(s.type() == null ? null : s.type().name());
        e.setFormat(s.format() == null ? null : s.format().name());
        e.setPath(s.path());
        e.setAddress(s.address());
        e.setTopic(s.topic());
        e.setEnv(s.env());
        e.setEnabled(s.enabled());
        e.setReadFrom(s.readFrom());
        e.setMultiline(s.multiline());
        e.setSinkTargetId(s.sinkTargetId());
        e.setParseRuleIdsJson(writeJson(s.parseRuleIds()));
        e.setDescription(s.description());
        e.setProtocol(s.protocol());
        e.setCharset(s.charset());
        e.setTimeField(s.timeField());
        e.setTimezone(s.timezone());
        e.setTagsJson(writeJson(s.tags()));
        e.setFrequency(s.frequency());
        e.setCategoryId(s.categoryId());
        e.setGroupId(s.groupId());
        e.setCreatedAt(s.createdAt());
        return e;
    }

    static LogSource fromEntity(LogSourceEntity e) {
        List<String> parseRuleIds = readList(e.getParseRuleIdsJson());
        List<String> tags = readList(e.getTagsJson());
        return new LogSource(e.getId(), e.getName(),
                e.getType() == null ? null : SourceType.valueOf(e.getType()),
                e.getFormat() == null ? null : ParseFormat.valueOf(e.getFormat()),
                e.getPath(), e.getAddress(), e.getTopic(), e.getEnv(), e.isEnabled(),
                e.getReadFrom(), e.getMultiline(), e.getSinkTargetId(),
                parseRuleIds == null ? List.of() : parseRuleIds, e.getDescription(),
                e.getProtocol(), e.getCharset(), e.getTimeField(), e.getTimezone(),
                tags == null ? List.of() : tags, e.getFrequency(), e.getCategoryId(), e.getGroupId(),
                e.getCreatedAt());
    }

    private static String writeJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return null;
        }
    }

    private static String tenant() {
        return TenantContext.require();
    }
}
