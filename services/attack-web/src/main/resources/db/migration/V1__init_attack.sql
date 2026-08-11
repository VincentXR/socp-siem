-- attack-web MITRE ATT&CK 目录持久化（静态目录落库：支持查询/排序，重启不丢）
CREATE TABLE IF NOT EXISTS t_tactic (
    id    VARCHAR(16)  PRIMARY KEY,
    name  VARCHAR(128) NOT NULL,
    sort  INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_technique (
    id          VARCHAR(16)  PRIMARY KEY,
    name        VARCHAR(256) NOT NULL,
    tactic      VARCHAR(16),
    url         VARCHAR(512),
    description VARCHAR(1024)
);
CREATE INDEX IF NOT EXISTS idx_t_technique_tactic ON t_technique (tactic);
