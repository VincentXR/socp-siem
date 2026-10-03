package com.socp.asset.web.persistence.store;

import com.socp.asset.web.domain.Asset;
import com.socp.asset.web.persistence.repository.AssetRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@Import({AssetStore.class, AssetCollectionStore.class})
@TestPropertySource(properties = {"socp.demo-data.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AssetCollectionPersistenceTest {
    @Autowired AssetStore manual;
    @Autowired AssetCollectionStore collection;
    @Autowired AssetRepository repository;
    @Autowired JdbcTemplate jdbc;
    @AfterEach void clear() { jdbc.update("delete from t_asset_discovery"); jdbc.update("delete from t_asset"); TenantContext.clear(); }

    @Test void concurrentDiscoveriesShareOneDatabaseIdentityAndKeepOtherTenantsSeparate() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        try {
            List<Future<Asset>> futures = java.util.stream.IntStream.range(0,8).mapToObj(i -> workers.submit(() -> {
                TenantContext.set("discovery-a"); ready.countDown(); start.await(5, TimeUnit.SECONDS);
                try { return collection.upsertByIp(asset("host-"+i," 203.0.113.7 ")); }
                finally { TenantContext.clear(); }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for(Future<Asset> result:futures) ids.add(result.get(20,TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);
            assertThat(repository.countByTenantId("discovery-a")).isEqualTo(1);
            TenantContext.set("discovery-b");
            assertThat(collection.upsertByIp(asset("other","203.0.113.7")).id()).isNotIn(ids);
        } finally { workers.shutdownNow(); }
    }

    @Test void concurrentDiscoveriesAdoptOnlyTheSingleUnclaimedManualAsset() throws Exception {
        TenantContext.set("concurrent-adopt");
        Asset existing = manual.save(asset("manual", "203.0.113.12"));
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        try {
            List<Future<Asset>> futures = java.util.stream.IntStream.range(0, 8).mapToObj(i -> workers.submit(() -> {
                TenantContext.set("concurrent-adopt");
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try { return collection.upsertByIp(asset("host-" + i, "203.0.113.12")); }
                finally { TenantContext.clear(); }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Asset> result : futures) assertThat(result.get(20, TimeUnit.SECONDS).id()).isEqualTo(existing.id());
            assertThat(manual.count()).isEqualTo(1);
        } finally { workers.shutdownNow(); }
    }

    @Test void manualNatDuplicatesAreNeverMergedOrOverwritten() {
        TenantContext.set("nat");
        Asset first=manual.save(asset("manual-a","203.0.113.8"));
        Asset second=manual.save(asset("manual-b","203.0.113.8"));
        Asset discovered=collection.upsertByIp(asset("discovered","203.0.113.8"));
        assertThat(discovered.id()).isNotIn(first.id(),second.id());
        assertThat(collection.upsertByIp(asset("renamed","203.0.113.8")).id()).isEqualTo(discovered.id());
        assertThat(manual.get(first.id()).name()).isEqualTo("manual-a");
        assertThat(manual.get(second.id()).name()).isEqualTo("manual-b");
        assertThat(manual.count()).isEqualTo(3);
        assertThat(manual.delete(discovered.id())).isTrue();
        assertThat(collection.upsertByIp(asset("new","203.0.113.8")).id()).isNotEqualTo(discovered.id());
    }

    @Test void singleExistingAssetIsAdoptedButBlankIpsRemainIndependent() {
        TenantContext.set("adopt");
        Asset existing=manual.save(asset("old","203.0.113.9"));
        assertThat(collection.upsertByIp(asset("new","203.0.113.9")).id()).isEqualTo(existing.id());
        assertThat(collection.upsertByIp(asset("blank1","")).id()).isNotEqualTo(collection.upsertByIp(asset("blank2"," ")).id());
    }
    @Test void changedManualIpCannotMergeDistinctDiscoveryIdentities() {
        TenantContext.set("changed-ip");
        Asset first = collection.upsertByIp(asset("first-discovery", "203.0.113.10"));
        manual.save(new Asset(first.id(), "manual-edit", first.type(), "203.0.113.11",
                first.os(), first.owner(), first.criticality(), first.createdAt()));

        Asset second = collection.upsertByIp(asset("second-discovery", "203.0.113.11"));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(collection.upsertByIp(asset("first-again", "203.0.113.10")).id()).isEqualTo(first.id());
        assertThat(collection.upsertByIp(asset("second-again", "203.0.113.11")).id()).isEqualTo(second.id());
        assertThat(manual.get(first.id()).ip()).isEqualTo("203.0.113.10");
        assertThat(manual.get(second.id()).ip()).isEqualTo("203.0.113.11");
    }

    private static Asset asset(String name,String ip) { return Asset.create(name,"SERVER",ip,"Linux","sec","HIGH"); }
}
