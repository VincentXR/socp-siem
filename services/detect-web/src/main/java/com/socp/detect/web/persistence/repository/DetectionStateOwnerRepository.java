package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.DetectionStateOwnerEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** Database-side compare-and-set operations for Detection state ownership. */
public interface DetectionStateOwnerRepository extends JpaRepository<DetectionStateOwnerEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DetectionStateOwnerEntity o where o.ownerKey = :ownerKey")
    Optional<DetectionStateOwnerEntity> findByOwnerKeyForUpdate(@Param("ownerKey") String ownerKey);

    /** Explicit insert avoids JpaRepository.save treating a manual id as merge. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = "insert into t_detection_state_owner "
            + "(owner_key, input_topic, input_partition, state_shard, owner_id, "
            + "fencing_epoch, lease_until, updated_at) "
            + "values (:ownerKey, :inputTopic, :partition, :shard, :ownerId, "
            + ":fencingEpoch, :leaseUntil, :now)", nativeQuery = true)
    int insert(@Param("ownerKey") String ownerKey,
               @Param("inputTopic") String inputTopic,
               @Param("partition") int partition,
               @Param("shard") int shard,
               @Param("ownerId") String ownerId,
               @Param("fencingEpoch") long fencingEpoch,
               @Param("leaseUntil") Instant leaseUntil,
               @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update DetectionStateOwnerEntity o set o.ownerId = :ownerId, "
            + "o.fencingEpoch = o.fencingEpoch + 1, o.leaseUntil = :leaseUntil, "
            + "o.updatedAt = :now where o.ownerKey = :ownerKey "
            + "and (o.ownerId = :ownerId or o.leaseUntil <= :now)")
    int claimExisting(@Param("ownerKey") String ownerKey,
                      @Param("ownerId") String ownerId,
                      @Param("now") Instant now,
                      @Param("leaseUntil") Instant leaseUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update DetectionStateOwnerEntity o set o.leaseUntil = :leaseUntil, "
            + "o.updatedAt = :now where o.ownerKey = :ownerKey and o.ownerId = :ownerId "
            + "and o.fencingEpoch = :fencingEpoch")
    int touch(@Param("ownerKey") String ownerKey,
              @Param("ownerId") String ownerId,
              @Param("fencingEpoch") long fencingEpoch,
              @Param("now") Instant now,
              @Param("leaseUntil") Instant leaseUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update DetectionStateOwnerEntity o set o.leaseUntil = :now, "
            + "o.fencingEpoch = o.fencingEpoch + 1, "
            + "o.updatedAt = :now where o.ownerKey = :ownerKey and o.ownerId = :ownerId "
            + "and o.fencingEpoch = :fencingEpoch")
    int release(@Param("ownerKey") String ownerKey,
                @Param("ownerId") String ownerId,
                @Param("fencingEpoch") long fencingEpoch,
                @Param("now") Instant now);
}
