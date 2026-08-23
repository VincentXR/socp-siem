UPDATE t_alarm_disposition SET tenant_id = 'default'
WHERE tenant_id IS NULL OR tenant_id = '';
ALTER TABLE t_alarm_disposition ALTER COLUMN tenant_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_alarm_disp_tenant_alarm
    ON t_alarm_disposition (tenant_id, alarm_id);
