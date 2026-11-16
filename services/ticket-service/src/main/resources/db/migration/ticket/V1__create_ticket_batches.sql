CREATE TABLE ticket_batches (
    booking_id bigint PRIMARY KEY CHECK (booking_id > 0),
    customer_id bigint NOT NULL CHECK (customer_id > 0),
    message_id uuid NOT NULL,
    fingerprint varchar(64) NOT NULL,
    version bigint NOT NULL,
    CONSTRAINT uk_ticket_message UNIQUE (message_id),
    CONSTRAINT ck_ticket_fingerprint CHECK (fingerprint ~ '^[a-f0-9]{64}$')
);
CREATE INDEX ix_ticket_batch_customer ON ticket_batches(customer_id);
