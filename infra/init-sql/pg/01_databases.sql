-- SOCP 按域拆库（§4）：13 个独立 schema/库，消除原 GaussDB 三共享库耦合
-- 在 postgres 容器首次启动时由 /docker-entrypoint-initdb.d 自动执行

CREATE DATABASE soc;
CREATE DATABASE asset;
CREATE DATABASE alert;
CREATE DATABASE search;
CREATE DATABASE detect;
CREATE DATABASE hips;
CREATE DATABASE soar;
CREATE DATABASE report;
CREATE DATABASE audit;
CREATE DATABASE ai;
CREATE DATABASE platform;
CREATE DATABASE incident;   -- incident-web 案件库（2026-08-08 接线新增）
CREATE DATABASE threat;          -- threat-web 情报库（2026-08-08 接线新增）
CREATE DATABASE attack;          -- attack-web ATT&CK 目录库（2026-08-12 H2→PG 新增）
CREATE DATABASE notify;          -- notify-web 通知渠道库（2026-08-12 H2→PG 新增）
CREATE DATABASE detect_model;    -- detect-model 二次分析库（2026-08-12 H2→PG 新增，避免与 detect-web 同库 Flyway 冲突）
