CREATE TABLE event_seat_holds (
                                  event_id BIGINT NOT NULL REFERENCES events(id),
                                  request_id VARCHAR(100) NOT NULL,
                                  quantity INTEGER NOT NULL CHECK (quantity > 0),
                                  status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED')),
                                  PRIMARY KEY (event_id, request_id)
);