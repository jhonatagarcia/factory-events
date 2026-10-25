CREATE TABLE manual_event_counter (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    next_value BIGINT NOT NULL CHECK (next_value > 0)
);

INSERT INTO manual_event_counter (id, next_value)
SELECT 1, COALESCE(MAX(SUBSTRING(event_id FROM 5)::BIGINT), 0) + 1
FROM events
WHERE event_id ~ '^EVT-[0-9]{1,12}$';

CREATE TABLE manual_event_requests (
    request_id UUID PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL UNIQUE REFERENCES events(event_id),
    payload_hash VARCHAR(64) NOT NULL
);
