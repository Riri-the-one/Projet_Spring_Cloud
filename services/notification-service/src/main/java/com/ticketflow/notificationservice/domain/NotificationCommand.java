package com.ticketflow.notificationservice.domain;
import java.time.Instant;
import java.util.UUID;
/** Internal normalized command. No accepted Booking/Payment transport contract yet. */
public record NotificationCommand(UUID eventId, long bookingId, long customerId, Kind kind, Instant occurredAt) {
    public enum Kind { CONFIRMATION, FAILURE, CANCELLATION }
    public NotificationCommand {
        if(eventId==null || bookingId<1 || customerId<1 || kind==null || occurredAt==null) throw new IllegalArgumentException("INVALID_NOTIFICATION");
        occurredAt=occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(occurredAt.isBefore(Instant.EPOCH)||occurredAt.isAfter(Instant.parse("9999-12-31T23:59:59Z")))throw new IllegalArgumentException("INVALID_NOTIFICATION_TIME");
    }
}
