package com.socp.threat.web.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.threat.web.domain.Ioc;
import com.socp.threat.web.config.LegacyConfidenceTypeCallback;
import com.socp.threat.web.persistence.repository.IocRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(LegacyConfidenceTypeCallback.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IocStorePersistenceTest {
    @Autowired IocRepository repository;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void sameIndicatorInTwoTenantsAndTwoFeedsRemainsIndependent() {
        IocStore store = new IocStore(repository, false);
        String firstTenant = UUID.randomUUID().toString();
        String secondTenant = UUID.randomUUID().toString();
        TenantContext.set(firstTenant);
        Ioc first = store.add(external("feed-a", "HIGH", false));
        Ioc second = store.add(external("feed-b", "CRITICAL", false));
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(store.match("192.0.2.7").id()).isEqualTo(second.id());
        Ioc revoked = store.add(external("feed-b", "CRITICAL", true));
        assertThat(revoked.id()).isEqualTo(second.id());
        assertThat(store.match("192.0.2.7").id()).isEqualTo(first.id());
        TenantContext.set(secondTenant);
        Ioc otherTenant = store.add(external("feed-a", "LOW", false));
        assertThat(otherTenant.id()).isNotEqualTo(first.id());
        assertThat(store.get(first.id())).isNull();
        assertThat(store.match("192.0.2.7").id()).isEqualTo(otherTenant.id());
    }

    @Test
    void punctuationDoesNotCollapseDifferentValuesAndManualRetriesUpsert() {
        TenantContext.set(UUID.randomUUID().toString());
        IocStore store = new IocStore(repository, false);
        Ioc one = store.add(Ioc.of("URL", "https://example.test/a?b", "HIGH", "manual", "", List.of()));
        Ioc two = store.add(Ioc.of("URL", "https://example.test/a/b", "HIGH", "manual", "", List.of()));
        assertThat(one.id()).isNotEqualTo(two.id());
        Ioc replay = store.add(Ioc.of("URL", one.value(), "LOW", "manual", "updated", List.of()));
        assertThat(replay.id()).isEqualTo(one.id());
        assertThat(store.count()).isEqualTo(2);
    }

    @Test
    void anotherInstanceObservesDeletionAndRollbackCannotLeavePhantomMatches() {
        TenantContext.set(UUID.randomUUID().toString());
        IocStore writer = new IocStore(repository, false);
        IocStore reader = new IocStore(repository, false);
        Ioc initial = writer.add(external("feed", "HIGH", false));
        assertThat(reader.match(initial.value())).isNotNull();
        writer.delete(initial.id());
        assertThat(reader.match(initial.value())).isNull();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            writer.add(external("feed", "HIGH", false));
            assertThat(writer.match("192.0.2.7")).isNotNull();
            status.setRollbackOnly();
        });
        assertThat(reader.match("192.0.2.7")).isNull();
    }

    private static Ioc external(String feed, String severity, boolean revoked) {
        return Ioc.external("IP", "192.0.2.7", severity, feed, "indicator--stable", "", List.of(),
                90d, "TLP:CLEAR", Instant.EPOCH, null, null, revoked, "taxii");
    }
}
