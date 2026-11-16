package com.ticketflow.notificationservice.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="notification_quarantine",uniqueConstraints=@UniqueConstraint(name="uk_notification_offset",columnNames={"topic","partition_id","record_offset"}))
public class Quarantine {
    @Id private UUID id;
    @Column(nullable=false,length=200) private String topic;
    @Column(name="partition_id",nullable=false) private int partition;
    @Column(name="record_offset",nullable=false) private long offset;
    @Column(nullable=false,length=64) private String payloadHash;
    @Column(nullable=false,length=40) private String reason;
    @Column(nullable=false) private Instant createdAt;
    @Version private Long version;
    protected Quarantine(){}
    public Quarantine(String topic,int partition,long offset,String hash,String reason,Instant at){id=UUID.randomUUID();this.topic=topic;this.partition=partition;this.offset=offset;payloadHash=hash;this.reason=reason;createdAt=at;}
    public UUID getId(){return id;} public String getTopic(){return topic;} public int getPartition(){return partition;}
    public long getOffset(){return offset;} public String getReason(){return reason;} public Instant getCreatedAt(){return createdAt;}
}
