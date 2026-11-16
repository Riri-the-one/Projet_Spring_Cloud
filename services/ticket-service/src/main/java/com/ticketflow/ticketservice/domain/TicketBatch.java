package com.ticketflow.ticketservice.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "ticket_batches", uniqueConstraints = @UniqueConstraint(name = "uk_ticket_message", columnNames = "message_id"))
public class TicketBatch {
    @Id private Long bookingId;
    @Column(nullable = false, updatable = false) private Long customerId;
    @Column(name = "message_id", nullable = false, updatable = false) private UUID messageId;
    @Column(nullable = false, updatable = false, length = 64) private String fingerprint;
    @Version private Long version;
    protected TicketBatch() {}
    public TicketBatch(long bookingId, long customerId, UUID messageId, String fingerprint) {
        this.bookingId = bookingId; this.customerId = customerId; this.messageId = messageId; this.fingerprint = fingerprint;
    }
    public Long getBookingId() { return bookingId; }
    public Long getCustomerId() { return customerId; }
    public String getFingerprint() { return fingerprint; }
}
