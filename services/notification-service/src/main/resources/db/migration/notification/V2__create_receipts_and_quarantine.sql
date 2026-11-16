CREATE TABLE simulated_receipts (
    event_id uuid PRIMARY KEY REFERENCES notifications(event_id),
    delivered_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL
);
CREATE TABLE notification_quarantine (
    id uuid PRIMARY KEY,
    topic varchar(200) NOT NULL,
    partition_id integer NOT NULL,
    record_offset bigint NOT NULL,
    payload_hash varchar(64) NOT NULL,
    reason varchar(40) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    CONSTRAINT uk_notification_offset UNIQUE(topic,partition_id,record_offset)
);
