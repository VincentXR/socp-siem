package com.socp.alert.repository;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

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
        contentQuery.where(predicates(cb, root, tenant, query));
        contentQuery.orderBy(orders(cb, root, query));

        TypedQuery<Alarm> typed = entityManager.createQuery(contentQuery);
        typed.setFirstResult(Math.toIntExact(pageable.getOffset()));
        typed.setMaxResults(pageable.getPageSize());
        List<Alarm> content = typed.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Alarm> countRoot = countQuery.from(Alarm.class);
        countQuery.select(cb.count(countRoot));
        countQuery.where(predicates(cb, countRoot, tenant, query));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public List<Alarm> list(String tenant, AlarmQuery query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Alarm> contentQuery = cb.createQuery(Alarm.class);
        Root<Alarm> root = contentQuery.from(Alarm.class);
        contentQuery.where(predicates(cb, root, tenant, query));
        contentQuery.orderBy(orders(cb, root, query));
        return entityManager.createQuery(contentQuery).getResultList();
    }

    private static Predicate[] predicates(CriteriaBuilder cb, Root<Alarm> root,
                                          String tenant, AlarmQuery query) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.<String>get("tenantId"), tenant));
        if (query.severity() != null) {
            predicates.add(cb.equal(root.<Severity>get("severity"), query.severity()));
        }
        if (query.rule() != null) {
            predicates.add(cb.equal(root.<String>get("ruleId"), query.rule()));
        }
        if (query.status() != null) {
            predicates.add(cb.equal(root.<String>get("status"), query.status()));
        }
        if (query.text() != null) {
            String pattern = "%" + escapeLike(query.text().toLowerCase(Locale.ROOT)) + "%";
            predicates.add(cb.or(
                    cb.like(cb.lower(root.<String>get("entity")), pattern, '\\'),
                    cb.like(cb.lower(root.<String>get("ruleName")), pattern, '\\'),
                    cb.like(cb.lower(root.<String>get("message")), pattern, '\\')));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private static List<Order> orders(CriteriaBuilder cb, Root<Alarm> root, AlarmQuery query) {
        Path<?> rawValue = switch (query.sort()) {
            case OCCURRED_AT -> root.get("occurredAt");
            case ALERT_CREATED_AT -> root.get("alertCreatedAt");
            case SEVERITY -> root.get("severity");
            case RULE_NAME -> root.get("ruleName");
            case ENTITY -> root.get("entity");
            case STATUS -> root.get("status");
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

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
