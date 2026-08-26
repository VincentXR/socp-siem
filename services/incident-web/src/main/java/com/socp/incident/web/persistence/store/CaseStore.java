package com.socp.incident.web.persistence.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.persistence.entity.CaseEntity;
import com.socp.incident.web.persistence.entity.CaseTimelineEntity;
import com.socp.incident.web.persistence.repository.CaseRepository;
import com.socp.incident.web.persistence.repository.CaseTimelineRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Tenant-scoped case persistence with an append-only normalized timeline. */
@Component
public class CaseStore {

    private final CaseRepository repo;
    private final CaseTimelineRepository timelineRepo;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final List<String> OPEN_STATUSES = List.of("OPEN", "INVESTIGATING");

    /** Compatibility constructor for focused unit tests without JPA timeline wiring. */
    public CaseStore(CaseRepository repo) {
        this(repo, null);
    }

    public CaseStore(CaseRepository repo, CaseTimelineRepository timelineRepo) {
        this.repo = repo;
        this.timelineRepo = timelineRepo;
    }

    @Transactional
    public Case save(Case c) {
        String tenant = tenant();
        CaseEntity entity = toEntity(c);
        entity.setTenantId(tenant);
        repo.save(entity);
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

    public Case get(String id) {
        return repo.findByTenantIdAndId(tenant(), id).map(this::fromEntity).orElse(null);
    }

    public String openCaseId(String entity) {
        if (entity == null) return null;
        List<CaseEntity> open = repo.findByTenantIdAndEntityAndStatusIn(tenant(), entity, OPEN_STATUSES);
        return open.isEmpty() ? null : open.get(0).getId();
    }

    /** Append one event by unique key; no JSON read-modify-write is involved. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        if (timelineRepo.findByTenantIdAndCaseIdAndEventKey(tenant, caseId, row.getEventKey()).isPresent()) {
            return false;
        }
        try {
            timelineRepo.saveAndFlush(row);
            repo.touchUpdatedAt(tenant, caseId, Instant.now());
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            // The unique tenant/case/event key is the concurrency oracle. A
            // competing request already appended the same logical event.
            return false;
        }
    }

    public Page<CaseTimelineEntity> timeline(String caseId, int page, int size) {
        String tenant = tenant();
        if (timelineRepo == null) return Page.empty(PageRequest.of(Math.max(0, page), Math.max(1, size)));
        return timelineRepo.findByTenantIdAndCaseIdOrderByTsAsc(tenant, caseId,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(500, size))));
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
        List<String> ruleIds = readList(entity.getRuleIdsJson(), new TypeReference<>() { });
        List<String> alarmIds = readList(entity.getAlarmIdsJson(), new TypeReference<>() { });
        List<TimelineEvent> timeline = timelineRepo == null ? null : timelineRepo
                .findByTenantIdAndCaseIdOrderByTsAsc(entity.getTenantId(), entity.getId()).stream()
                .limit(500)
                .map(CaseStore::fromTimelineEntity)
                .toList();
        if (timeline == null || timeline.isEmpty()) {
            timeline = readList(entity.getTimelineJson(), new TypeReference<>() { });
        }
        return new Case(entity.getId(), entity.getCaseNo(), entity.getTitle(), entity.getEntity(),
                entity.getSeverity(), entity.getStatus(),
                ruleIds == null ? List.of() : ruleIds,
                alarmIds == null ? List.of() : alarmIds,
                timeline == null ? List.of() : timeline,
                entity.getAssignee(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getRowVersion());
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
