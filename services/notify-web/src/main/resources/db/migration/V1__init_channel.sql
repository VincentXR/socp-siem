-- notify-web 通知渠道持久化（替代内存态：渠道配置重启不丢）
CREATE TABLE IF NOT EXISTS t_channel (
    id          VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    target      VARCHAR(512),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    description VARCHAR(512)
);
