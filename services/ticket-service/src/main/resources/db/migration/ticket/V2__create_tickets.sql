CREATE TABLE tickets (
    id uuid PRIMARY KEY,
    booking_id bigint NOT NULL REFERENCES ticket_batches(booking_id),
    line_id varchar(64) NOT NULL CHECK (length(trim(line_id)) > 0),
    seat_index integer NOT NULL CHECK (seat_index > 0),
    event_id bigint NOT NULL CHECK (event_id > 0),
    control_code varchar(43) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ISSUED','USED','VOIDED','EXPIRED')),
    issued_at timestamp(6) with time zone NOT NULL,
    valid_until timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    CONSTRAINT uk_ticket_seat UNIQUE (booking_id, line_id, seat_index),
    CONSTRAINT uk_ticket_control UNIQUE (control_code),
    CONSTRAINT ck_ticket_code CHECK (control_code ~ '^[A-Za-z0-9_-]{43}$')
);
