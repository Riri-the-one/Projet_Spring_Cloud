package com.ticketflow.notificationservice;
import static org.assertj.core.api.Assertions.*;
import com.ticketflow.notificationservice.domain.*;
import com.ticketflow.notificationservice.repo.*;
import com.ticketflow.notificationservice.service.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
@SpringBootTest @ActiveProfiles("postgresql")
class NotificationPersistenceIT {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry p){NotificationPostgresqlProperties.register(p);}
    @Autowired NotificationInbox inbox;@Autowired DeliveryWorker worker;@Autowired NotificationRepo repo;@Autowired ReceiptRepo receipts;
    @Test void pendingDeliveryAndDeduplicationSurviveDatabaseAndJvmRestart()throws Exception{
        Path path=Path.of(System.getenv("NOTIFICATION_TEST_MARKER"));Properties marker=new Properties();boolean existing=Files.exists(path);
        if(existing){try(var in=Files.newInputStream(path)){marker.load(in);}}
        else{marker.setProperty("id",UUID.randomUUID().toString());marker.setProperty("at",Instant.now().toString());}
        var c=new NotificationCommand(UUID.fromString(marker.getProperty("id")),123,456,NotificationCommand.Kind.CANCELLATION,Instant.parse(marker.getProperty("at")));
        inbox.accept(c);
        if(!existing){worker.deliver(c.eventId());assertThat(repo.findById(c.eventId()).orElseThrow().getAttempts()).isEqualTo(1);try(var out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){marker.store(out,"Technical test marker, no secret or personal data");}}
        else{assertThat(repo.findById(c.eventId()).orElseThrow().getAttempts()).isEqualTo(1);worker.deliver(c.eventId());worker.deliver(c.eventId());inbox.accept(c);worker.deliver(c.eventId());assertThat(repo.findById(c.eventId()).orElseThrow().getAttempts()).isEqualTo(3);assertThat(receipts.findById(c.eventId())).isPresent();}
    }
}
