package com.ticketflow.notificationservice.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="notifications")
public class Notification {
    public enum Status { PENDING, RETRY, SENT, DEAD }
    @Id private UUID eventId;
    @Column(nullable=false,updatable=false) private long bookingId;
    @Column(nullable=false,updatable=false) private long customerId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,updatable=false,length=20) private NotificationCommand.Kind kind;
    @Column(nullable=false,updatable=false) private Instant occurredAt;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Status status=Status.PENDING;
    @Column(nullable=false) private int attempts;
    @Column(nullable=false) private int roundAttempts;
    @Column(nullable=false) private int manualRetries;
    @Column(nullable=false) private Instant nextAttemptAt;
    private Instant sentAt;
    @Version private Long version;
    protected Notification(){}
    public Notification(NotificationCommand c,Instant now){eventId=c.eventId();bookingId=c.bookingId();customerId=c.customerId();kind=c.kind();occurredAt=c.occurredAt();createdAt=now;nextAttemptAt=now;}
    public boolean same(NotificationCommand c){return bookingId==c.bookingId() && customerId==c.customerId() && kind==c.kind() && occurredAt.equals(c.occurredAt());}
    public void attempt(){attempts++;roundAttempts++;}
    public void sent(Instant now){status=Status.SENT;sentAt=now;}
    public void failed(Instant now,int maximum,long backoffMillis){status=roundAttempts>=maximum?Status.DEAD:Status.RETRY;nextAttemptAt=now.plusMillis(backoffMillis);}
    public void retry(Instant now){if(status!=Status.DEAD)throw new IllegalStateException("ONLY_DEAD_CAN_RETRY");status=Status.RETRY;roundAttempts=0;manualRetries++;nextAttemptAt=now;}
    public UUID getEventId(){return eventId;} public long getBookingId(){return bookingId;} public long getCustomerId(){return customerId;}
    public NotificationCommand.Kind getKind(){return kind;} public Status getStatus(){return status;} public int getAttempts(){return attempts;}
    public int getManualRetries(){return manualRetries;} public Instant getCreatedAt(){return createdAt;} public Instant getSentAt(){return sentAt;}
    public Instant getNextAttemptAt(){return nextAttemptAt;}
}
