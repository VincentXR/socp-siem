package com.socp.platform.audit.aspect;

import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.audit.sink.JdbcAuditOutboxSink;
import com.socp.platform.tenant.context.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionalAuditAspectTest {

    private JdbcTemplate jdbc;
    private AuditAspect aspect;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:audit-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE t_business (id VARCHAR(32) PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE t_audit_outbox (
                  event_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
                  action VARCHAR(128) NOT NULL, operator_id VARCHAR(255) NOT NULL,
                  target_name VARCHAR(255) NOT NULL, result_text VARCHAR(1024) NOT NULL,
                  payload TEXT NOT NULL, status VARCHAR(16) NOT NULL,
                  attempt_count INTEGER NOT NULL, next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  claimed_at TIMESTAMP WITH TIME ZONE, claim_token VARCHAR(64),
                  published_at TIMESTAMP WITH TIME ZONE, last_error VARCHAR(1024),
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL)
                """);
        aspect = new AuditAspect(new JdbcAuditOutboxSink(jdbc),
                new DataSourceTransactionManager(dataSource));
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void commitsBusinessMutationAndAuditRowAtomically() throws Throwable {
        ProceedingJoinPoint point = point(() -> {
            jdbc.update("INSERT INTO t_business (id) VALUES ('ok')");
            return "done";
        });

        assertThat(aspect.around(point)).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_business", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT result_text FROM t_audit_outbox", String.class))
                .isEqualTo("SUCCESS");
    }

    @Test
    void rollsBackBusinessMutationWhenDurableAuditInsertFails() throws Throwable {
        jdbc.execute("DROP TABLE t_audit_outbox");
        ProceedingJoinPoint point = point(() -> {
            jdbc.update("INSERT INTO t_business (id) VALUES ('rolled-back')");
            return "done";
        });

        assertThatThrownBy(() -> aspect.around(point)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_business", Integer.class)).isZero();
    }

    @Test
    void rollsBackFailedMutationButCommitsFailureAuditInNewTransaction() throws Throwable {
        IllegalStateException operationFailure = new IllegalStateException("business rejected");
        ProceedingJoinPoint point = point(() -> {
            jdbc.update("INSERT INTO t_business (id) VALUES ('failed')");
            throw operationFailure;
        });

        assertThatThrownBy(() -> aspect.around(point)).isSameAs(operationFailure);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_business", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT result_text FROM t_audit_outbox", String.class))
                .isEqualTo("FAIL:business rejected");
    }

    @Test
    void honorsDeclaredNoRollbackRulesAndCommitsTheFailureAuditTogether() throws Throwable {
        IllegalArgumentException operationFailure = new IllegalArgumentException("expected rejection");
        ProceedingJoinPoint point = point("toleratedFailure", () -> {
            jdbc.update("INSERT INTO t_business (id) VALUES ('retained')");
            throw operationFailure;
        });

        assertThatThrownBy(() -> aspect.around(point)).isSameAs(operationFailure);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_business", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT result_text FROM t_audit_outbox", String.class))
                .isEqualTo("FAIL:expected rejection");
    }

    private static ProceedingJoinPoint point(Invocation invocation) throws Throwable {
        return point("mutate", invocation);
    }

    private static ProceedingJoinPoint point(String methodName, Invocation invocation) throws Throwable {
        Method method = Target.class.getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        when(point.getSignature()).thenReturn(signature);
        when(point.getTarget()).thenReturn(new Target());
        when(point.proceed()).thenAnswer(ignored -> invocation.invoke());
        return point;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke() throws Throwable;
    }

    static final class Target {
        @AuditOperation(action = "MUTATE", target = "business")
        Object mutate() {
            return null;
        }

        @AuditOperation(action = "TOLERATED", target = "business")
        @Transactional(noRollbackFor = IllegalArgumentException.class,
                isolation = Isolation.SERIALIZABLE, timeout = 3)
        public Object toleratedFailure() {
            return null;
        }
    }
}
