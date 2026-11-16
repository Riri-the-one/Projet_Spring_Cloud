package com.ticketflow.notificationservice.kafka;
import com.ticketflow.notificationservice.domain.Quarantine;
import com.ticketflow.notificationservice.repo.QuarantineRepo;
import com.ticketflow.notificationservice.service.NotificationInbox;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Component @ConditionalOnProperty(name="ticketflow.notification.ingress",havingValue="probe")
public class ProbeListener {
    private final ProbeDecoder decoder;private final NotificationInbox inbox;private final QuarantineRepo quarantine;private final TransactionTemplate tx;private final Clock clock;
    public ProbeListener(ProbeDecoder decoder,NotificationInbox inbox,QuarantineRepo quarantine,PlatformTransactionManager manager,Clock clock){this.decoder=decoder;this.inbox=inbox;this.quarantine=quarantine;this.tx=new TransactionTemplate(manager);this.clock=clock;}
    @KafkaListener(topics="ticketflow.infra.notification.probe.v1",groupId="${ticketflow.notification.probe-group:ticketflow-notification-probe-v1}")
    public void receive(ConsumerRecord<String,String> record){
        try{inbox.accept(decoder.decode(record.value()));}
        catch(IllegalArgumentException invalid){quarantine(record,"INVALID_PROBE");}
        catch(NotificationInbox.Conflict conflict){quarantine(record,"EVENT_ID_CONFLICT");}
        // Storage errors escape: never acknowledge a record whose durable transaction failed.
    }
    private void quarantine(ConsumerRecord<String,String> record,String reason){
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(record.value()).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException();}
        try{tx.executeWithoutResult(status->{if(!quarantine.existsByTopicAndPartitionAndOffset(record.topic(),record.partition(),record.offset()))
            quarantine.saveAndFlush(new Quarantine(record.topic(),record.partition(),record.offset(),hash,reason,clock.instant()));});}
        catch(DataIntegrityViolationException concurrent){
            Boolean exists=tx.execute(status->quarantine.existsByTopicAndPartitionAndOffset(record.topic(),record.partition(),record.offset()));
            if(!Boolean.TRUE.equals(exists))throw concurrent;
        }
    }
}
