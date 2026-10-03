package com.socp.asset.web.persistence.store;

import com.socp.asset.web.domain.Asset;
import com.socp.asset.web.persistence.entity.AssetEntity;
import com.socp.asset.web.persistence.repository.AssetRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Database-owned discovery identity, independent of manually entered NAT/shared-IP assets. */
@Component
public class AssetCollectionStore {
    private final AssetRepository assets;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AssetCollectionStore(AssetRepository assets, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.assets = assets;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        // Each attempt must finish/roll back before a competing unique-key insert is retried.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Asset upsertByIp(Asset input) {
        String tenant = TenantContext.require();
        String ip = input.ip() == null ? "" : input.ip().trim().toLowerCase(Locale.ROOT);
        Asset normalized = new Asset(input.id(), input.name(), input.type(), ip, input.os(), input.owner(),
                input.criticality(), input.createdAt());
        for (int attempt = 0; ; attempt++) {
            try {
                return Objects.requireNonNull(transaction.execute(status -> upsert(tenant, ip, normalized)));
            } catch (DataIntegrityViolationException conflict) {
                if (ip.isEmpty() || attempt >= 2) throw conflict;
                // A uniqueness loser restarts in a new transaction and reads the committed winner.
            }
        }
    }

    private Asset upsert(String tenant, String ip, Asset input) {
        if (ip.isEmpty()) return AssetStore.fromEntity(assets.saveAndFlush(AssetStore.toEntity(input, tenant)));
        // Lock assets consistently before writing identities; an inverse identity-first
        // lock order can deadlock adoption against an update of a concurrent winner.
        List<String> identities = jdbc.queryForList(
                "select asset_id from t_asset_discovery where tenant_id = ? and normalized_ip = ?",
                String.class, tenant, ip);
        AssetEntity target;
        boolean insertIdentity = identities.isEmpty();
        if (insertIdentity) {
            // Adopt one existing match for compatibility; never overwrite an arbitrary NAT duplicate.
            List<AssetEntity> candidates = assets.findCollectionCandidate(tenant, ip,
                    PageRequest.of(0, 2, Sort.by("id")));
            boolean unclaimed = candidates.size() == 1 && jdbc.queryForList(
                    "select asset_id from t_asset_discovery where asset_id = ?", String.class,
                    candidates.getFirst().getId()).isEmpty();
            // A manual IP edit must not make an existing discovered asset serve two IPs.
            target = unclaimed ? candidates.getFirst() : AssetStore.toEntity(input, tenant);
        } else {
            target = assets.findCollectionTarget(identities.getFirst(), tenant)
                    .orElseThrow(() -> new IllegalStateException("discovery identity has no asset"));
        }
        target.setName(input.name()); target.setType(input.type()); target.setIp(ip);
        target.setOs(input.os()); target.setOwner(input.owner()); target.setCriticality(input.criticality());
        assets.saveAndFlush(target);
        if (insertIdentity) jdbc.update("insert into t_asset_discovery (tenant_id, normalized_ip, asset_id) values (?, ?, ?)",
                tenant, ip, target.getId());
        return AssetStore.fromEntity(target);
    }
}
