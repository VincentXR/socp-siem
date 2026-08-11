-- hips-web 端点注册表持久化（替代内存态：重启不丢，接口不变）
CREATE TABLE IF NOT EXISTS t_endpoint (
    id             VARCHAR(64)  PRIMARY KEY,
    hostname       VARCHAR(128) NOT NULL,
    ip             VARCHAR(64),
    os             VARCHAR(64),
    agent_version  VARCHAR(64),
    status         VARCHAR(32)  NOT NULL DEFAULT 'OFFLINE',
    last_heartbeat TIMESTAMP(6) WITH TIME ZONE
);
