-- Shared tenant directory.  Tenant codes are stable external identifiers;
-- UUID ids remain the API primary key for backwards compatibility.
CREATE TABLE IF NOT EXISTS t_tenant (
    id          VARCHAR(64) PRIMARY KEY,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    user_count  INTEGER NOT NULL DEFAULT 0,
    alarm_count INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_t_tenant_code ON t_tenant (code);
