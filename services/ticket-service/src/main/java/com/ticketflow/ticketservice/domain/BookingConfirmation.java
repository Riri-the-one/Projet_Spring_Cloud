package com.ticketflow.ticketservice.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Proposed internal input, NOT an HTTP DTO or an accepted Kafka contract. */
public record BookingConfirmation(UUID messageId, int schemaVersion, String source, long bookingId,
        long customerId, long bookingVersion, String status, String paymentStatus, String paymentId,
        Instant occurredAt, List<Line> lines) {
    public BookingConfirmation { if (lines != null) lines = List.copyOf(lines); }
    public record Line(String lineId, long eventId, int quantity, Instant validUntil) {}
}
