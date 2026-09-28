package com.socp.incident.web.persistence.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.persistence.entity.CaseEntity;
import com.socp.incident.web.persistence.entity.CaseTimelineEntity;
import com.socp.incident.web.persistence.entity.AlarmCaseLinkEntity;
import com.socp.incident.web.persistence.entity.CaseRuleLinkEntity;
import com.socp.incident.web.persistence.repository.CaseRepository;
import com.socp.incident.web.persistence.repository.CaseTimelineRepository;
import com.socp.incident.web.persistence.repository.AlarmCaseLinkRepository;
import com.socp.incident.web.persistence.repository.CaseRuleLinkRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;

/** Tenant-scoped case persistence with an append-only normalized timeline. */
@Component
public class CaseStore {

    private final CaseRepository repo;
    private final CaseTimelineRepository timelineRepo;
    private final AlarmCaseLinkRepository alarmLinkRepo;
    private final CaseRuleLinkRepository ruleLinkRepo;
    private final boolean postgres;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final List<String> OPEN_STATUSES = List.of("OPEN", "INVESTIGATING", "CONTAINED");

    /** Compatibility constructor for focused unit tests without JPA timeline wiring. */
    public CaseStore(CaseRepository repo) {
        this(repo, null, null, null, true);
    }

    public CaseStore(CaseRepository repo, CaseTimelineRepository timelineRepo) {
        this(repo, timelineRepo, null, null, true);
    }

    @Autowired
    public CaseStore(CaseRepository repo, CaseTimelineRepository timelineRepo,
                     AlarmCaseLinkRepository alarmLinkRepo, CaseRuleLinkRepository ruleLinkRepo,
                     JdbcTemplate jdbc) {
        this(repo, timelineRepo, alarmLinkRepo, ruleLinkRepo,
                "PostgreSQL".equals(jdbc.execute((ConnectionCallback<String>) connection ->
                        connection.getMetaData().getDatabaseProductName())));
    }

    CaseStore(CaseRepository repo, CaseTimelineRepository timelineRepo,
              AlarmCaseLinkRepository alarmLinkRepo, CaseRuleLinkRepository ruleLinkRepo,
              boolean postgres) {
        this.repo = repo;
        this.timelineRepo = timelineRepo;
        this.alarmLinkRepo = alarmLinkRepo;
        this.ruleLinkRepo = ruleLinkRepo;
        this.postgres = postgres;
    }

    @Transactional
    public Case save(Case c) {
        String tenant = tenant();
        CaseEntity entity = toEntity(c);
        entity.setTenantId(tenant);
        if (alarmLinkRepo != null && ruleLinkRepo != null) {
            entity.setAlarmIdsJson("[]");
            entity.setRuleIdsJson("[]");
            entity.setTimelineJson("[]");
        }
        repo.save(entity);
        persistAssociations(c, tenant);
        if (timelineRepo != null) {
            for (TimelineEvent event : c.timeline()) {
                CaseTimelineEntity row = toTimelineEntity(c.id(), event, tenant);
                if (timelineRepo.findByTenantIdAndCaseIdAndEventKey(tenant, c.id(), row.getEventKey()).isEmpty()) {
                    timelineRepo.save(row);
                }
            }
        }
        return c;
    }

    public List<Case> list() {
        String tenant = tenant();
        List<Case> all = new ArrayList<>();
        for (CaseEntity entity : repo.findByTenantId(tenant)) all.add(fromEntity(entity));
        all.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return all;
    }

    /** Reads one bounded tenant page without loading each case timeline. */
    @Transactional(readOnly = true)
    public Page<Case> page(int page, int size, String query, String status) {
        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id")));
        String normalizedQuery = query == null ? "" : query.trim();
        String normalizedStatus = status == null ? "" : status.trim();
        String tenant = tenant();
        Page<CaseEntity> entities = repo.searchByTenantId(tenant, normalizedQuery, normalizedStatus, pageable);
        if (entities.isEmpty() || alarmLinkRepo == null || ruleLinkRepo == null) {
            return entities.map(entity -> fromEntity(entity, false));
        }
        List<String> ids = entities.getContent().stream().map(CaseEntity::getId).toList();
        Map<String, Long> alarmCounts = groupedCounts(alarmLinkRepo.countByCaseIds(tenant, ids));
        Map<String, Long> ruleCounts = groupedCounts(ruleLinkRepo.countByCaseIds(tenant, ids));
        return entities.map(entity -> fromEntitySummary(entity,
                associationCount(ruleCounts.get(entity.getId()), legacyRules(entity),
                        () -> normalizedRuleIds(entity)),
                associationCount(alarmCounts.get(entity.getId()), legacyAlarms(entity),
                        () -> normalizedAlarmIds(entity))));
    }

    public long count() {
        return repo.countByTenantId(tenant());
    }

    public long countByStatusIn(List<String> statuses) {
        return repo.countByTenantIdAndStatusIn(tenant(), statuses);
    }

    public Case get(String id) {
        return repo.findByTenantIdAndId(tenant(), id).map(this::fromEntity).orElse(null);
    }

    /** Metadata and counts only; mutation paths must not hydrate unbounded associations/timeline. */
    @Transactional(readOnly = true)
    public Case getMetadata(String id) {
        String tenant = tenant();
        return repo.findByTenantIdAndId(tenant, id)
                .map(entity -> fromEntitySummary(entity,
                        associationCount(ruleLinkRepo == null ? null
                                        : ruleLinkRepo.countByTenantIdAndCaseId(tenant, id),
                                legacyRules(entity), () -> normalizedRuleIds(entity)),
                        associationCount(alarmLinkRepo == null ? null
                                        : alarmLinkRepo.countByTenantIdAndCaseId(tenant, id),
                                legacyAlarms(entity), () -> normalizedAlarmIds(entity))))
                .orElse(null);
    }

    public String openCaseId(String entity) {
        if (entity == null || entity.isBlank()) return null;
        return repo.findFirstByTenantIdAndEntityAndStatusInOrderByUpdatedAtDescIdAsc(
                tenant(), entity, OPEN_STATUSES).map(CaseEntity::getId).orElse(null);
    }

    /** Append one event by unique key; no JSON read-modify-write is involved. */
    @Transactional
    public boolean appendTimeline(String caseId, TimelineEvent event) {
        String tenant = tenant();
        if (repo.findByTenantIdAndId(tenant, caseId).isEmpty()) return false;
        if (timelineRepo == null) {
            Case current = get(caseId);
            if (current == null) return false;
            List<TimelineEvent> events = new ArrayList<>(current.timeline());
            events.add(event);
            save(new Case(current.id(), current.caseNo(), current.title(), current.entity(), current.severity(),
                    current.status(), current.ruleIds(), current.alarmIds(), events, current.assignee(),
                    current.createdAt(), Instant.now()));
            return true;
        }
        CaseTimelineEntity row = toTimelineEntity(caseId, event, tenant);
        int inserted;
        if (postgres) {
            inserted = timelineRepo.insertIfAbsent(row.getId(), row.getTenantId(), row.getCaseId(),
                    row.getEventKey(), row.getTs(), row.getType(), row.getMessage(), row.getSource(),
                    row.getAlarmId(), row.getCreatedAt());
        } else if (timelineRepo.findByTenantIdAndCaseIdAndEventKey(
                tenant, caseId, row.getEventKey()).isEmpty()) {
            timelineRepo.save(row);
            inserted = 1;
        } else {
            inserted = 0;
        }
        if (inserted == 1) {
            repo.touchUpdatedAt(tenant, caseId, Instant.now());
            return true;
        }
        // PostgreSQL's conflict-safe insert is the concurrency oracle. Unlike
        // catching a uniqueness exception, it does not mark the shared
        // business/audit transaction rollback-only.
        return false;
    }

    /** Update mutable case metadata without traversing every historical association. */
    @Transactional
    public Case saveMetadata(Case incident) {
        String tenant = tenant();
        CaseEntity entity = repo.findByTenantIdAndId(tenant, incident.id())
                .orElseThrow(() -> new IllegalStateException("Case disappeared during update"));
        entity.setTitle(incident.title());
        entity.setEntity(incident.entity());
        entity.setSeverity(incident.severity());
        entity.setStatus(incident.status());
        entity.setAssignee(incident.assignee());
        entity.setUpdatedAt(incident.updatedAt());
        repo.save(entity);
        return incident;
    }

    /** Persist only the one association delta represented by an incoming alarm. */
    @Transactional
    public Case saveAlarmDelta(Case incident, String ruleId, String alarmId, TimelineEvent event) {
        Case saved = saveMetadata(incident);
        String tenant = tenant();
        Instant now = Instant.now();
        if (alarmLinkRepo != null && alarmId != null && !alarmId.isBlank()) {
            insertAlarmLink(tenant, incident.id(), alarmId, now);
        }
        if (ruleLinkRepo != null && ruleId != null && !ruleId.isBlank()) {
            insertRuleLink(tenant, incident.id(), ruleId, now);
        }
        appendTimeline(incident.id(), event);
        return saved;
    }

    public Page<CaseTimelineEntity> timeline(String caseId, int page, int size) {
        String tenant = tenant();
        if (timelineRepo == null) return Page.empty(PageRequest.of(Math.max(0, page), Math.max(1, size)));
        return timelineRepo.findByTenantIdAndCaseIdOrderByTsAsc(tenant, caseId,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(500, size)), Sort.by("id")));
    }

    public Page<String> alarms(String caseId, int page, int size) {
        String tenant = tenant();
        PageRequest request = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(500, size)));
        if (alarmLinkRepo == null) return legacyPage(get(caseId), true, request);
        CaseEntity entity = repo.findByTenantIdAndId(tenant, caseId).orElse(null);
        if (entity == null) return Page.empty(request);
        List<String> legacy = legacyAlarms(entity);
        if (!legacy.isEmpty()) return pageOf(mergeDistinct(normalizedAlarmIds(entity), legacy), request);
        Page<String> normalized = alarmLinkRepo
                .findByTenantIdAndCaseIdOrderByAlarmIdAsc(tenant, caseId, request)
                .map(AlarmCaseLinkEntity::getAlarmId);
        return normalized;
    }

    public Page<String> rules(String caseId, int page, int size) {
        String tenant = tenant();
        PageRequest request = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(500, size)));
        if (ruleLinkRepo == null) return legacyPage(get(caseId), false, request);
        CaseEntity entity = repo.findByTenantIdAndId(tenant, caseId).orElse(null);
        if (entity == null) return Page.empty(request);
        List<String> legacy = legacyRules(entity);
        if (!legacy.isEmpty()) return pageOf(mergeDistinct(normalizedRuleIds(entity), legacy), request);
        Page<String> normalized = ruleLinkRepo
                .findByTenantIdAndCaseIdOrderByRuleIdAsc(tenant, caseId, request)
                .map(CaseRuleLinkEntity::getRuleId);
        return normalized;
    }

    private static String tenant() {
        return TenantContext.require();
    }

    static CaseEntity toEntity(Case c) {
        CaseEntity entity = new CaseEntity();
        entity.setId(c.id());
        entity.setCaseNo(c.caseNo());
        entity.setTitle(c.title());
        entity.setEntity(c.entity());
        entity.setSeverity(c.severity());
        entity.setStatus(c.status());
        entity.setRuleIdsJson(writeJson(c.ruleIds()));
        entity.setAlarmIdsJson(writeJson(c.alarmIds()));
        entity.setTimelineJson(writeJson(c.timeline()));
        entity.setAssignee(c.assignee());
        entity.setCreatedAt(c.createdAt());
        entity.setUpdatedAt(c.updatedAt());
        entity.setRowVersion(c.rowVersion());
        return entity;
    }

    private Case fromEntity(CaseEntity entity) {
        return fromEntity(entity, true);
    }

    private Case fromEntity(CaseEntity entity, boolean includeTimeline) {
        return fromEntity(entity, includeTimeline, null, null);
    }

    private Case fromEntity(CaseEntity entity, boolean includeTimeline,
                            List<String> suppliedRules, List<String> suppliedAlarms) {
        List<String> ruleIds = suppliedRules == null ? normalizedRules(entity) : suppliedRules;
        List<String> alarmIds = suppliedAlarms == null ? normalizedAlarms(entity) : suppliedAlarms;
        List<TimelineEvent> timeline = List.of();
        if (includeTimeline) {
            timeline = timelineRepo == null ? null : timelineRepo
                    .findTop500ByTenantIdAndCaseIdOrderByTsAscIdAsc(entity.getTenantId(), entity.getId()).stream()
                    .map(CaseStore::fromTimelineEntity)
                    .toList();
            if (timeline == null || timeline.isEmpty()) {
                timeline = readList(entity.getTimelineJson(), new TypeReference<>() { });
            }
        }
        return new Case(entity.getId(), entity.getCaseNo(), entity.getTitle(), entity.getEntity(),
                entity.getSeverity(), entity.getStatus(),
                ruleIds == null ? List.of() : ruleIds,
                alarmIds == null ? List.of() : alarmIds,
                timeline == null ? List.of() : timeline,
                entity.getAssignee(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getRowVersion(),
                ruleIds == null ? 0 : ruleIds.size(), alarmIds == null ? 0 : alarmIds.size());
    }

    private Case fromEntitySummary(CaseEntity entity, long ruleCount, long alarmCount) {
        return new Case(entity.getId(), entity.getCaseNo(), entity.getTitle(), entity.getEntity(),
                entity.getSeverity(), entity.getStatus(), List.of(), List.of(), List.of(),
                entity.getAssignee(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getRowVersion(),
                ruleCount, alarmCount);
    }

    private static CaseTimelineEntity toTimelineEntity(String caseId, TimelineEvent event, String tenant) {
        String key = event.idempotencyKey();
        if (key == null || key.isBlank()) {
            key = "event:" + UUID.nameUUIDFromBytes((String.valueOf(event.ts()) + "\u0000"
                    + event.type() + "\u0000" + event.message()).getBytes(StandardCharsets.UTF_8));
        }
        CaseTimelineEntity entity = new CaseTimelineEntity();
        entity.setId(UUID.nameUUIDFromBytes((tenant + "\u0000" + caseId + "\u0000" + key)
                .getBytes(StandardCharsets.UTF_8)).toString());
        entity.setTenantId(tenant);
        entity.setCaseId(caseId);
        entity.setEventKey(key);
        entity.setTs(event.ts());
        entity.setType(event.type());
        entity.setMessage(event.message());
        entity.setSource(event.source());
        entity.setAlarmId(event.alarmId());
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private static TimelineEvent fromTimelineEntity(CaseTimelineEntity entity) {
        return new TimelineEvent(entity.getTs(), entity.getType(), entity.getMessage(), entity.getSource(),
                entity.getAlarmId(), entity.getEventKey());
    }

    private void persistAssociations(Case incident, String tenant) {
        if (alarmLinkRepo == null || ruleLinkRepo == null) return;
        Instant now = Instant.now();
        for (String alarmId : incident.alarmIds()) {
            if (alarmId == null || alarmId.isBlank()) continue;
            insertAlarmLink(tenant, incident.id(), alarmId, now);
        }
        for (String ruleId : incident.ruleIds()) {
            if (ruleId == null || ruleId.isBlank()) continue;
            insertRuleLink(tenant, incident.id(), ruleId, now);
        }
    }

    private void insertAlarmLink(String tenant, String caseId, String alarmId, Instant now) {
        String id = UUID.nameUUIDFromBytes((tenant + "\u0000" + alarmId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        if (postgres) {
            alarmLinkRepo.insertIfAbsent(id, tenant, alarmId, caseId, now);
            return;
        }
        if (alarmLinkRepo.findByTenantIdAndAlarmId(tenant, alarmId).isPresent()) return;
        AlarmCaseLinkEntity link = new AlarmCaseLinkEntity();
        link.setId(id); link.setTenantId(tenant); link.setAlarmId(alarmId);
        link.setCaseId(caseId); link.setCreatedAt(now);
        alarmLinkRepo.save(link);
    }

    private void insertRuleLink(String tenant, String caseId, String ruleId, Instant now) {
        if (postgres) {
            ruleLinkRepo.insertIfAbsent(stableLinkId(tenant, caseId, ruleId), tenant, caseId, ruleId, now);
            return;
        }
        if (ruleLinkRepo.existsByTenantIdAndCaseIdAndRuleId(tenant, caseId, ruleId)) return;
        CaseRuleLinkEntity link = new CaseRuleLinkEntity();
        link.setId(stableLinkId(tenant, caseId, ruleId)); link.setTenantId(tenant);
        link.setCaseId(caseId); link.setRuleId(ruleId); link.setCreatedAt(now);
        ruleLinkRepo.save(link);
    }

    private static Map<String, Long> groupedCounts(List<Object[]> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (rows == null) return counts;
        for (Object[] row : rows) {
            if (row != null && row.length >= 2 && row[0] != null && row[1] instanceof Number value) {
                counts.put(String.valueOf(row[0]), value.longValue());
            }
        }
        return counts;
    }

    private static long associationCount(Long normalized, List<String> legacy,
                                         java.util.function.Supplier<List<String>> normalizedIds) {
        long count = normalized == null ? 0 : normalized;
        if (legacy == null || legacy.isEmpty()) return count;
        if (count == 0) return legacy.size();
        return mergeDistinct(normalizedIds.get(), legacy).size();
    }

    private static Page<String> legacyPage(Case incident, boolean alarms, PageRequest request) {
        if (incident == null) return Page.empty(request);
        return pageOf(alarms ? incident.alarmIds() : incident.ruleIds(), request);
    }

    private static Page<String> pageOf(List<String> values, PageRequest request) {
        List<String> safe = values == null ? List.of() : values;
        int start = Math.min((int) request.getOffset(), safe.size());
        int end = Math.min(start + request.getPageSize(), safe.size());
        return new org.springframework.data.domain.PageImpl<>(safe.subList(start, end), request, safe.size());
    }

    private List<String> normalizedAlarms(CaseEntity entity) {
        return mergeDistinct(normalizedAlarmIds(entity), legacyAlarms(entity));
    }

    private List<String> normalizedRules(CaseEntity entity) {
        return mergeDistinct(normalizedRuleIds(entity), legacyRules(entity));
    }

    private List<String> normalizedAlarmIds(CaseEntity entity) {
        if (alarmLinkRepo == null) return List.of();
        return alarmLinkRepo.findByTenantIdAndCaseIdOrderByAlarmIdAsc(
                        entity.getTenantId(), entity.getId()).stream()
                .map(AlarmCaseLinkEntity::getAlarmId).toList();
    }

    private List<String> normalizedRuleIds(CaseEntity entity) {
        if (ruleLinkRepo == null) return List.of();
        return ruleLinkRepo.findByTenantIdAndCaseIdOrderByRuleIdAsc(
                        entity.getTenantId(), entity.getId()).stream()
                .map(CaseRuleLinkEntity::getRuleId).toList();
    }

    private static List<String> mergeDistinct(List<String> normalized, List<String> legacy) {
        java.util.TreeSet<String> merged = new java.util.TreeSet<>();
        if (normalized != null) merged.addAll(normalized);
        if (legacy != null) merged.addAll(legacy);
        return List.copyOf(merged);
    }

    private static List<String> legacyAlarms(CaseEntity entity) {
        List<String> legacy = readList(entity.getAlarmIdsJson(), new TypeReference<>() { });
        return legacy == null ? List.of() : legacy;
    }

    private static List<String> legacyRules(CaseEntity entity) {
        List<String> legacy = readList(entity.getRuleIdsJson(), new TypeReference<>() { });
        return legacy == null ? List.of() : legacy;
    }

    private static String stableLinkId(String tenant, String caseId, String value) {
        return UUID.nameUUIDFromBytes((tenant + "\u0000" + caseId + "\u0000" + value)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private static <T> T readList(String json, TypeReference<T> ref) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, ref);
        } catch (Exception ignored) {
            return null;
        }
    }
}
