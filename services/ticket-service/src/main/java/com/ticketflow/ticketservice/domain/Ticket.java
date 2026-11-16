package com.ticketflow.ticketservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ticket_seat", columnNames = {"booking_id", "line_id", "seat_index"}),
        @UniqueConstraint(name = "uk_ticket_control", columnNames = "control_code")})
public class Ticket {
    @Id private UUID id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false) private TicketBatch batch;
    @Column(name = "line_id", nullable = false, updatable = false, length = 64) private String lineId;
    @Column(name = "seat_index", nullable = false, updatable = false) private int seatIndex;
    @Column(nullable = false, updatable = false) private Long eventId;
    @Column(name = "control_code", nullable = false, updatable = false, length = 43) private String controlCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private TicketStatus status = TicketStatus.ISSUED;
    @Column(nullable = false, updatable = false) private Instant issuedAt;
    @Column(nullable = false, updatable = false) private Instant validUntil;
    @Version private Long version;
    protected Ticket() {}
    public Ticket(TicketBatch batch, String lineId, int seatIndex, long eventId, String controlCode, Instant issuedAt, Instant validUntil) {
        this.id = UUID.randomUUID(); this.batch = batch; this.lineId = lineId; this.seatIndex = seatIndex;
        this.eventId = eventId; this.controlCode = controlCode; this.issuedAt = issuedAt; this.validUntil = validUntil;
    }
    public TicketStatus statusAt(Instant now) { return status == TicketStatus.ISSUED && !now.isBefore(validUntil) ? TicketStatus.EXPIRED : status; }
    public void markUsed(Instant now) {
        if (statusAt(now) != TicketStatus.ISSUED) throw new IllegalStateException("Ticket is not usable");
        status = TicketStatus.USED;
    }
    public void voidTicket() { status = TicketStatus.VOIDED; }
    public UUID getId() { return id; }
    public TicketBatch getBatch() { return batch; }
    public String getLineId() { return lineId; }
    public int getSeatIndex() { return seatIndex; }
    public Long getEventId() { return eventId; }
    public String getControlCode() { return controlCode; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getValidUntil() { return validUntil; }
}
