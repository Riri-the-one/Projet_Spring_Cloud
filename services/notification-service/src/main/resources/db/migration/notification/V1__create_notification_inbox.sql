CREATE TABLE notifications (
    event_id uuid PRIMARY KEY,
    booking_id bigint NOT NULL CHECK (booking_id>0),
    customer_id bigint NOT NULL CHECK (customer_id>0),
    kind varchar(20) NOT NULL CHECK (kind IN ('CONFIRMATION','FAILURE','CANCELLATION')),
    occurred_at timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','RETRY','SENT','DEAD')),
    attempts integer NOT NULL CHECK (attempts>=0),
    round_attempts integer NOT NULL CHECK (round_attempts>=0),
    manual_retries integer NOT NULL CHECK (manual_retries>=0),
    next_attempt_at timestamp(6) with time zone NOT NULL,
    sent_at timestamp(6) with time zone,
    version bigint NOT NULL
);
CREATE INDEX ix_notification_due ON notifications(status,next_attempt_at);
CREATE INDEX ix_notification_customer ON notifications(customer_id,created_at);
