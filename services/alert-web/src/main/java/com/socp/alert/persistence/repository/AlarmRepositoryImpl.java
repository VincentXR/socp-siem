package com.socp.alert.persistence.repository;

import com.socp.alert.domain.Alarm;
import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.domain.AlarmEvidence;
import com.socp.alert.domain.AlarmQuery;
import com.socp.alert.domain.Severity;


import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Criteria implementation instead of materialising every tenant alarm in the JVM.
 * The explicit sort expressions preserve the UI's severity ordering and put null
 * values last consistently across PostgreSQL and H2.
 */
@Repository
public class AlarmRepositoryImpl implements AlarmRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<Alarm> page(String tenant, AlarmQuery query, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Alarm> contentQuery = cb.createQuery(Alarm.class);
        Root<Alarm> root = contentQuery.from(Alarm.class);
        contentQuery.where(predicates(cb, contentQuery, root, tenant, query));
        contentQuery.orderBy(orders(cb, contentQuery, root, tenant, query));

        TypedQuery<Alarm> typed = entityManager.createQuery(contentQuery);
        typed.setFirstResult(Math.toIntExact(pageable.getOffset()));
        typed.setMaxResults(pageable.getPageSize());
        List<Alarm> content = typed.getResultList();
        if (!content.isEmpty()) {
            var states = entityManager.createQuery("select d from DispositionEntity d where d.tenantId = :tenant and d.alarmId in :ids", DispositionEntity.class)
                    .setParameter("tenant", tenant).setParameter("ids", content.stream().map(Alarm::getId).toList()).getResultList();
            var byId = states.stream().collect(java.util.stream.Collectors.toMap(DispositionEntity::getAlarmId, d -> d));
            content.forEach(alarm -> { var state = byId.get(alarm.getId()); if (state != null) {
                alarm.setAssignee(state.getAssignee()); alarm.setDispositionStatus(state.getStatus());
            }});
        }

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Alarm> countRoot = countQuery.from(Alarm.class);
        countQuery.select(cb.count(countRoot));
        countQuery.where(predicates(cb, countQuery, countRoot, tenant, query));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public long count(String tenant, AlarmQuery query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Alarm> countRoot = countQuery.from(Alarm.class);
        countQuery.select(cb.count(countRoot));
        countQuery.where(predicates(cb, countQuery, countRoot, tenant, query));
        return entityManager.createQuery(countQuery).getSingleResult();
    }

    private static Predicate[] predicates(CriteriaBuilder cb, jakarta.persistence.criteria.CriteriaQuery<?> owner,
                                          Root<Alarm> root,
                                          String tenant, AlarmQuery query) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.<String>get("tenantId"), tenant));
        if (query.severity() != null) {
            predicates.add(cb.equal(root.<Severity>get("severity"), query.severity()));
        }
        if (query.rule() != null) {
            predicates.add(cb.equal(root.<String>get("ruleId"), query.rule()));
        }
        if (query.technique() != null && !query.technique().isBlank()) predicates.add(cb.equal(cb.upper(root.get("mitre")), query.technique().toUpperCase(java.util.Locale.ROOT)));
        if ("high".equals(query.severityGroup())) predicates.add(root.get("severity").in(Severity.HIGH, Severity.CRITICAL));
        if (query.entity() != null && !query.entity().isBlank()) predicates.add(cb.equal(root.<String>get("entity"), query.entity()));
        if (query.status() != null || (query.owner() != null && !query.owner().isBlank())) {
            Subquery<Integer> anyDisposition = owner.subquery(Integer.class);
            Root<DispositionEntity> any = anyDisposition.from(DispositionEntity.class);
            anyDisposition.select(cb.literal(1)).where(cb.equal(any.get("tenantId"), tenant), cb.equal(any.get("alarmId"), root.get("id")));
            if (query.status() != null) {
                var status = effectiveStatus(cb, owner, root, tenant);
                predicates.add("ACTIVE".equals(query.status())
                        ? status.in(com.socp.alert.domain.AlarmState.activeNames())
                        : cb.equal(status, query.status()));
            }
            if (query.owner() != null && !query.owner().isBlank()) {
                Subquery<Integer> ownerMatch = owner.subquery(Integer.class);
                Root<DispositionEntity> d = ownerMatch.from(DispositionEntity.class);
                boolean unassigned = "unassigned".equals(query.owner());
                Predicate target = unassigned ? cb.or(cb.isNull(d.get("assignee")), cb.equal(d.get("assignee"), "")) : cb.equal(d.get("assignee"), query.owner());
                ownerMatch.select(cb.literal(1)).where(cb.equal(d.get("tenantId"), tenant), cb.equal(d.get("alarmId"), root.get("id")), target);
                predicates.add(unassigned ? cb.or(cb.exists(ownerMatch), cb.not(cb.exists(anyDisposition))) : cb.exists(ownerMatch));
            }
        }
        if (query.from() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), query.from()));
        if (query.to() != null) predicates.add(query.inclusiveTo()
                ? cb.lessThanOrEqualTo(root.get("occurredAt"), query.to())
                : cb.lessThan(root.get("occurredAt"), query.to()));
        if (query.assignee() != null) {
            Subquery<Integer> assigned = owner.subquery(Integer.class);
            var disposition = assigned.from(com.socp.alert.persistence.entity.DispositionEntity.class);
            assigned.select(cb.literal(1));
            assigned.where(cb.equal(disposition.get("tenantId"), tenant),
                    cb.equal(disposition.get("alarmId"), root.get("id")),
                    cb.equal(disposition.get("assignee"), query.assignee()));
            predicates.add(cb.exists(assigned));
        }
        if (query.text() != null) {
            String pattern = "%" + escapeLike(query.text().toLowerCase(Locale.ROOT)) + "%";
            Subquery<Integer> evidenceMatch = owner.subquery(Integer.class);
            Root<AlarmEvidence> evidence = evidenceMatch.from(AlarmEvidence.class);
            evidenceMatch.select(cb.literal(1));
            evidenceMatch.where(
                    cb.equal(evidence.<String>get("tenantId"), tenant),
                    cb.equal(evidence.<String>get("alarmId"), root.<String>get("id")),
                    cb.like(cb.lower(evidence.<String>get("eventId")), pattern, '\\'));
            predicates.add(cb.or(
                    cb.like(cb.lower(root.<String>get("entity")), pattern, '\\'),
                    cb.like(cb.lower(root.<String>get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.<String>get("ruleName")), pattern, '\\'),
                    cb.like(cb.lower(root.<String>get("message")), pattern, '\\'),
                    cb.like(cb.lower(root.<String>get("triggerEventId")), pattern, '\\'),
                    cb.exists(evidenceMatch)));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private static List<Order> orders(CriteriaBuilder cb, CriteriaQuery<?> owner, Root<Alarm> root, String tenant, AlarmQuery query) {
        Expression<?> rawValue = switch (query.sort()) {
            case OCCURRED_AT -> root.get("occurredAt");
            case ALERT_CREATED_AT -> root.get("alertCreatedAt");
            case SEVERITY -> root.get("severity");
            case RULE_NAME -> root.get("ruleName");
            case ENTITY -> root.get("entity");
            case STATUS -> effectiveStatus(cb, owner, root, tenant);
            case RISK_SCORE -> root.get("riskScore");
        };
        Expression<?> sortValue = switch (query.sort()) {
            case SEVERITY -> severityRank(cb, root);
            case RULE_NAME, ENTITY, STATUS -> cb.lower(rawValue.as(String.class));
            default -> rawValue;
        };
        Expression<Integer> nullLast = cb.<Integer>selectCase()
                .when(cb.isNull(rawValue), 1)
                .otherwise(0);
        Order primary = query.ascending() ? cb.asc(sortValue) : cb.desc(sortValue);
        return List.of(cb.asc(nullLast), primary, cb.asc(root.<String>get("id")));
    }

    private static Expression<Integer> severityRank(CriteriaBuilder cb, Root<Alarm> root) {
        Path<Severity> severity = root.get("severity");
        return cb.<Integer>selectCase()
                .when(cb.equal(severity, Severity.CRITICAL), 5)
                .when(cb.equal(severity, Severity.HIGH), 4)
                .when(cb.equal(severity, Severity.MEDIUM), 3)
                .when(cb.equal(severity, Severity.LOW), 2)
                .when(cb.equal(severity, Severity.INFO), 1)
                .otherwise(0);
    }

    private static Expression<String> effectiveStatus(CriteriaBuilder cb, CriteriaQuery<?> owner, Root<Alarm> alarm, String tenant) {
        Subquery<String> state = owner.subquery(String.class);
        Root<DispositionEntity> disposition = state.from(DispositionEntity.class);
        state.select(disposition.get("status")).where(cb.equal(disposition.get("tenantId"), tenant),
                cb.equal(disposition.get("alarmId"), alarm.get("id")));
        return cb.coalesce(state, alarm.<String>get("status"));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
