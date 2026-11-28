package com.ticketflow.notificationservice.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="simulated_receipts")
public class SimulatedReceipt {
    @Id private UUID eventId;
    @Column(nullable=false,updatable=false) private Instant deliveredAt;
    @Version private Long version;
    protected SimulatedReceipt(){}
    public SimulatedReceipt(UUID id,Instant at){eventId=id;deliveredAt=at;}
    public UUID getEventId(){return eventId;}
}
