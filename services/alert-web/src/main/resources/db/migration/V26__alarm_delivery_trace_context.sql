-- New deliveries retain the actual parent span across asynchronous dispatch.
-- Existing rows remain deliverable; historical parent spans cannot be invented.
ALTER TABLE alarm_delivery ADD COLUMN traceparent VARCHAR(255);
