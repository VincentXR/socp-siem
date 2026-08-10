package com.socp.incident.web.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 案件存储——本地切片用 H2 文件库（重启不丢）；生产由案例库 PG 承载。
 * 对外公共 API（save/list/get/openCaseId）保持不变，CaseService 无需改动。
 */
@Component
public class CaseStore {

    private final CaseRepository repo;
    // Instant 需要 JavaTimeModule 才能与 JSON 文本列互转（默认 ObjectMapper 反序列化 Instant 会抛错 → timeline 变成空）
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final List<String> OPEN_STATUSES = List.of("OPEN", "INVESTIGATING");

    public CaseStore(CaseRepository repo) {
        this.repo = repo;
    }

    public Case save(Case c) {
        repo.save(toEntity(c));
        return c;
    }

    public List<Case> list() {
        String tenant = tenant();
        List<Case> all = new ArrayList<>();
        for (CaseEntity e : repo.findByTenantId(tenant)) {
            all.add(fromEntity(e));
        }
        all.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return all;
    }

    public Case get(String id) {
        return repo.findByTenantIdAndId(tenant(), id).map(CaseStore::fromEntity).orElse(null);
    }

    /** 查找某实体当前进行中的案件，无则 null（限当前租户）。 */
    public String openCaseId(String entity) {
        if (entity == null) return null;
        List<CaseEntity> open = repo.findByTenantIdAndEntityAndStatusIn(tenant(), entity, OPEN_STATUSES);
        return open.isEmpty() ? null : open.get(0).getId();
    }

    /** 当前租户（无上下文按 default），租户隔离查询统一入口。 */
    private static String tenant() {
        String t = com.socp.platform.tenant.TenantContext.get();
        return t == null ? "default" : t;
    }

    // ---- 互转 ----

    static CaseEntity toEntity(Case c) {
        CaseEntity e = new CaseEntity();
        e.setId(c.id());
        e.setCaseNo(c.caseNo());
        e.setTitle(c.title());
        e.setEntity(c.entity());
        e.setSeverity(c.severity());
        e.setStatus(c.status());
        e.setRuleIdsJson(writeJson(c.ruleIds()));
        e.setAlarmIdsJson(writeJson(c.alarmIds()));
        e.setTimelineJson(writeJson(c.timeline()));
        e.setAssignee(c.assignee());
        e.setCreatedAt(c.createdAt());
        e.setUpdatedAt(c.updatedAt());
        return e;
    }

    static Case fromEntity(CaseEntity e) {
        List<String> ruleIds = readList(e.getRuleIdsJson(), new TypeReference<>() {
        });
        List<String> alarmIds = readList(e.getAlarmIdsJson(), new TypeReference<>() {
        });
        List<TimelineEvent> timeline = readList(e.getTimelineJson(), new TypeReference<>() {
        });
        return new Case(e.getId(), e.getCaseNo(), e.getTitle(), e.getEntity(), e.getSeverity(), e.getStatus(),
                ruleIds == null ? List.of() : ruleIds,
                alarmIds == null ? List.of() : alarmIds,
                timeline == null ? List.of() : timeline,
                e.getAssignee(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private static String writeJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static <T> T readList(String json, TypeReference<T> ref) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, ref);
        } catch (Exception ex) {
            return null;
        }
    }
}
