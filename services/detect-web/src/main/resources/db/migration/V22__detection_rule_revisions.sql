-- Durable rule-spec version chain and content-pack upgrade conflicts.
--
-- The rule lifecycle gap (review 2026-09, siem-gap High) was that t_rule kept a
-- single spec column, so a content-pack upgrade or an analyst edit overwrote the
-- previous definition with nothing to diff or roll back, and a customized rule
-- that a newer pack wanted to update was silently skipped with no record. This
-- migration adds two append-only tables:
--   * t_rule_revision  - every persisted mutation of t_rule appends one immutable
--     row (add/edit/activate/restore/delete) in the same transaction, giving an
--     inspectable chain and a non-destructive rollback source.
--   * t_rule_content_conflict - when syncPackagedContent finds a user-customized
--     rule whose content pack has a newer version, a pending conflict is recorded
--     instead of overwriting (or silently dropping) the local tuning.
--
-- New rows only; no published migration is edited. Indexes are plain (H2 and PG
-- both run this under the in-Pod transactional Flyway bean, so CONCURRENTLY is
-- not available here). The tables are small control-plane rows written only on
-- rule mutations, not on the event path, so the build lock is negligible.
CREATE TABLE IF NOT EXISTS t_rule_revision (
    id          VARCHAR(36)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    rule_id     VARCHAR(128) NOT NULL,
    revision    BIGINT       NOT NULL,
    spec        TEXT         NOT NULL,
    status      VARCHAR(32),
    source      VARCHAR(32)  NOT NULL,
    changed_by  VARCHAR(128),
    changed_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_rule_revision PRIMARY KEY (id),
    CONSTRAINT uq_rule_revision UNIQUE (tenant_id, rule_id, revision)
);

CREATE TABLE IF NOT EXISTS t_rule_content_conflict (
    id              VARCHAR(36)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    rule_id         VARCHAR(128) NOT NULL,
    content_pack    VARCHAR(128) NOT NULL,
    pack_version    VARCHAR(64)  NOT NULL,
    stored_version  VARCHAR(64),
    status          VARCHAR(16)  NOT NULL,
    detected_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    resolved_at     TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_rule_content_conflict PRIMARY KEY (id),
    CONSTRAINT uq_rule_content_conflict UNIQUE (tenant_id, rule_id, content_pack, pack_version)
);
