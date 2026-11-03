-- Age gauges need the first timestamp within a status, not a retained-history scan.
CREATE INDEX idx_alarm_delivery_status_created
    ON alarm_delivery (status, created_at);
CREATE INDEX idx_alarm_delivery_status_updated
    ON alarm_delivery (status, updated_at);
