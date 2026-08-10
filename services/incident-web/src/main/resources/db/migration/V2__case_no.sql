-- incident-web V2：案件新增人读展示编号 case_no，与内部主键 id(UUIDv7) 分离。
-- 背景：原 id 形如 CASE-<epochMilli>，并发建案会撞主键；现 id 改 UUIDv7，
-- case_no 仅用于 SOC  analyst 在界面/工单中引用（INC-<yyyyMMdd>-<随机>）。
-- 兼容老库：ALTER 仅加列，已有行 case_no 为 NULL，不影响历史案件。

ALTER TABLE t_case ADD COLUMN case_no VARCHAR(64);

CREATE INDEX idx_t_case_caseno ON t_case (case_no);
