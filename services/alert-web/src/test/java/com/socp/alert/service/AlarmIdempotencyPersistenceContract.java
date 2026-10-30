package com.socp.alert.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.alert.api.controller.AlarmController;
import com.socp.alert.persistence.repository.AlarmRepository;
import com.socp.alert.persistence.repository.AlarmBatchIdempotencyRepository;
import com.socp.platform.error.web.GlobalExceptionHandler;
import com.socp.platform.tenant.context.TenantContext;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Actual controller, service, transactions and storage; barriers synchronize real empty reads. */
@DataJpaTest(showSql = false)
@Import({AlarmService.class, AlarmQueryService.class, AlarmStatisticsService.class,
        AlarmDeliveryRegistrar.class, AlarmSuppressionService.class, AlarmSuppressionLock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AlarmIdempotencyPersistenceContract {
    @Autowired AlarmService service;
    @Autowired AlarmRepository alarms;
    @Autowired AlarmBatchIdempotencyRepository batches;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AlarmEnrichmentService enrichment;
    @MockitoBean OutboxPublisher publisher;
    @MockitoBean AlarmDeliveryPublisher deliveries;
    private final String tenant = UUID.randomUUID().toString();
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach void controller() {
        mvc = MockMvcBuilders.standaloneSetup(new AlarmController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void simultaneousSourceIdentityReturnsOneAlarmAndOneSetOfSideEffects() throws Exception {
        try (var gate = gate(alarms, "findByTenantIdAndSourceAlertId", "same")) {
            var results = race(false, body("same"), body("same"), null, null);
            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            String id = data(results.getFirst()).path("id").asText();
            assertThat(data(results.getLast()).path("id").asText()).isEqualTo(id).isNotBlank();
            assertRows(1, 0);
            jdbc.update("update t_alarm set status='CLOSED' where tenant_id=? and id=?", tenant, id);
            var replay = request(tenant, false, body("same"), null);
            assertThat(data(replay).path("status").asText()).isEqualTo("CLOSED");
            var other = request(tenant + "-other", false, body("same"), null);
            assertThat(other.getResponse().getStatus()).isEqualTo(200);
            assertThat(data(other).path("id").asText()).isNotEqualTo(id);
            assertRows(1, 0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentBatchKeyReplaysTheWinnerOrRejectsDifferentPayload(boolean different) throws Exception {
        try (var gate = gate(batches, "findByTenantIdAndIdempotencyKey", "key")) {
            var results = race(true, body("one"), body(different ? "two" : "one"), "key", "key");
            assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList())
                    .containsExactlyElementsOf(different ? List.of(200, 409) : List.of(200, 200));
            if (!different) assertThat(data(results.getFirst())).isEqualTo(data(results.getLast()));
            assertRows(1, 1);
        }
    }

    @Test
    void overlappingBatchesInReverseOrderDoNotDeadlockOrMisreportDuplicates() throws Exception {
        try (var gate = gate(alarms, "findByTenantIdAndSourceAlertId", "a")) {
            var results = race(true, body("a") + "," + body("b"), body("b") + "," + body("a"), "first", "second");
            assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
            assertThat(data(results.get(0)).path("alarms").get(0).path("sourceAlertId").asText()).isEqualTo("a");
            assertThat(data(results.get(1)).path("alarms").get(0).path("sourceAlertId").asText()).isEqualTo("b");
            assertThat(data(results.get(0)).path("accepted").size() + data(results.get(1)).path("accepted").size()).isEqualTo(2);
            assertThat(data(results.get(0)).path("duplicates").size() + data(results.get(1)).path("duplicates").size()).isEqualTo(2);
            assertRows(2, 2);
        }
    }

    @Test
    void unrelatedInsertFailureRollsBackTheClaimAlarmsAndAllSideEffects() throws Exception {
        String invalid = body("z").replace("\"title\":\"title\"", "\"title\":\"" + "x".repeat(600) + "\"");
        assertThat(request(tenant, true, body("a") + "," + invalid, "retry").getResponse().getStatus()).isEqualTo(500);
        assertRows(0, 0);
        assertThat(request(tenant, true, body("a") + "," + body("z"), "retry").getResponse().getStatus()).isEqualTo(200);
        assertRows(2, 1);
    }

    private List<MvcResult> race(boolean batch, String first, String second, String firstKey, String secondKey) throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> request(tenant, batch, first, firstKey));
            var b = workers.submit(() -> request(tenant, batch, second, secondKey));
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private AutoCloseable gate(Object repository, String method, String identity) {
        var barrier = new CyclicBarrier(2);
        var arrivals = new AtomicInteger();
        MethodInterceptor interceptor = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals(method) && invocation.getArguments()[0].equals(tenant)
                    && invocation.getArguments()[1].equals(identity) && ((Optional<?>) result).isEmpty()
                    && arrivals.incrementAndGet() <= 2) barrier.await(10, TimeUnit.SECONDS);
            return result;
        };
        ((Advised) repository).addAdvice(0, interceptor);
        return () -> ((Advised) repository).removeAdvice(interceptor);
    }

    private MvcResult request(String scope, boolean batch, String body, String key) throws Exception {
        try (var ignored = TenantContext.open(scope)) {
            var request = post(batch ? "/api/v1/alarms/batch" : "/api/v1/alarms").contentType("application/json")
                    .content(batch ? "{\"alarms\":[" + body + "]}" : body);
            if (key != null) request.header("Idempotency-Key", key);
            return mvc.perform(request).andReturn();
        }
    }

    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void assertRows(int alarmCount, int batchCount) {
        for (String table : List.of("t_alarm", "outbox_event", "t_alarm_evidence")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Integer.class, tenant))
                    .as(table).isEqualTo(alarmCount);
        }
        assertThat(jdbc.queryForObject("select count(*) from alarm_delivery where tenant_id=?", Integer.class, tenant))
                .isEqualTo(alarmCount * 5);
        assertThat(jdbc.queryForObject("select count(*) from t_alarm_batch_idempotency where tenant_id=?", Integer.class, tenant))
                .isEqualTo(batchCount);
        assertThat(jdbc.queryForObject("select count(*) from t_alarm where tenant_id=? and (created_at is null or updated_at is null)",
                Integer.class, tenant)).isZero();
    }

    private static String body(String source) {
        return "{\"ruleId\":\"rule\",\"ruleName\":\"Rule\",\"severity\":\"HIGH\",\"entity\":\"host\",\"message\":\"test\","
                + "\"sourceAlertId\":\"" + source + "\",\"title\":\"title\",\"riskScore\":70,"
                + "\"occurredAt\":\"2026-10-11T22:14:15Z\",\"triggerEventId\":\"event\","
                + "\"evidence\":[{\"eventId\":\"event\",\"timestamp\":\"2026-10-11T22:14:15Z\",\"raw\":\"evidence\",\"fields\":{}}]}";
    }
}
