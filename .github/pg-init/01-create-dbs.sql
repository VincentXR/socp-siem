-- CI PostgreSQL 初始化：业务库（GitHub Actions 挂载到 docker-entrypoint-initdb.d）
CREATE DATABASE alert;
CREATE DATABASE report;
CREATE DATABASE detect;
CREATE DATABASE search;
CREATE DATABASE incident;
CREATE DATABASE threat;
CREATE DATABASE asset;
CREATE DATABASE audit;
