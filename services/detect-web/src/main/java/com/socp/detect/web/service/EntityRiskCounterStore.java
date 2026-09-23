package com.socp.detect.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Exact counters; every write runs under the owning profile's transaction and row lock. */
@Service
public class EntityRiskCounterStore {
    private static final JsonMapper KEY_JSON = JsonMapper.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
    private final JdbcTemplate jdbc;

    public EntityRiskCounterStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void seed(String tenant, String profile, Map<String, Long> mitre, Map<String, Long> rules) {
        requireTenant(tenant);
        mitre.forEach((key, count) -> insert(tenant, profile, "MITRE", key, count));
        rules.forEach((key, count) -> insert(tenant, profile, "RULE", key, count));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void increment(String tenant, String profile, String dimension, String key) {
        requireTenant(tenant);
        validate(dimension, key);
        // Profile locking serializes both first insertion and existing-counter updates.
        String encoded = encode(key);
        String id = identity(tenant, profile, dimension, encoded);
        int changed = jdbc.update("""
                update t_entity_risk_counter set hit_count=hit_count+1
                where counter_id=? and tenant_id=? and profile_id=? and dimension=? and member_key_json=?
                """, id, tenant, profile, dimension, encoded);
        if (changed == 0) insert(tenant, profile, dimension, key, 1);
    }

    private void insert(String tenant, String profile, String dimension, String key, long count) {
        validate(dimension, key);
        if (count < 0) throw new IllegalStateException("Negative legacy entity-risk count requires repair");
        String encoded = encode(key);
        jdbc.update("""
                insert into t_entity_risk_counter(counter_id,tenant_id,profile_id,dimension,member_hash,member_key_json,hit_count)
                values (?,?,?,?,?,?,?)
                """, identity(tenant, profile, dimension, encoded), tenant, profile, dimension, hash(encoded), encoded, count);
    }

    public record Counters(List<Map<String, Object>> mitre, List<Map<String, Object>> rules) {
        public static final Counters EMPTY = new Counters(List.of(), List.of());
    }

    @Transactional(readOnly = true, timeout = 5)
    public Map<String, Counters> readTop(String tenant, List<String> profiles) {
        requireTenant(tenant);
        if (profiles.size() > 500) throw new IllegalArgumentException("At most 500 risk profiles per read");
        if (profiles.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(profiles.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenant);
        arguments.addAll(profiles);
        Map<String, Counters> result = new HashMap<>();
        jdbc.query("""
                select profile_id,dimension,member_key_json,hit_count from (
                  select profile_id,dimension,member_key_json,hit_count,
                    row_number() over (partition by profile_id,dimension order by hit_count desc,member_key_json asc) as rank_no
                  from t_entity_risk_counter where tenant_id=? and profile_id in (
                """ + placeholders + """
                )) ranked where (dimension='MITRE' and rank_no<=8) or (dimension='RULE' and rank_no<=5)
                order by profile_id,dimension,hit_count desc,member_key_json asc
                """, rs -> {
            String profile = rs.getString("profile_id");
            Counters counts = result.computeIfAbsent(profile, ignored -> new Counters(new ArrayList<>(), new ArrayList<>()));
            boolean mitre = "MITRE".equals(rs.getString("dimension"));
            (mitre ? counts.mitre() : counts.rules()).add(Map.of(
                    mitre ? "technique" : "rule", decode(rs.getString("member_key_json")), "count", rs.getLong("hit_count")));
        }, arguments.toArray());
        return result;
    }

    private static void requireTenant(String tenant) {
        if (!TenantContext.require().equals(tenant)) throw new IllegalStateException("Entity-risk counter tenant mismatch");
    }

    private static void validate(String dimension, String key) {
        if (!("MITRE".equals(dimension) || "RULE".equals(dimension)) || key == null || key.length() > 8192)
            throw new IllegalArgumentException("Invalid entity-risk counter key");
    }

    // ASCII JSON preserves control characters and exact UTF-16 keys in PostgreSQL TEXT.
    private static String encode(String key) {
        try { return KEY_JSON.writeValueAsString(key); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode risk counter key", failure); }
    }

    private static String decode(String encoded) {
        try { return KEY_JSON.readValue(encoded, String.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid stored risk counter key", failure); }
    }

    private static String identity(String tenant, String profile, String dimension, String key) {
        StringBuilder tuple = new StringBuilder();
        for (String part : List.of(tenant, profile, dimension, key)) tuple.append(part.length()).append(':').append(part);
        return hash(tuple.toString());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
