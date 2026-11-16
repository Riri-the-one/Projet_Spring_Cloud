package com.ticketflow.notificationservice;
import static org.assertj.core.api.Assertions.*;
import com.ticketflow.notificationservice.domain.*;
import com.ticketflow.notificationservice.repo.*;
import com.ticketflow.notificationservice.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@SpringBootTest(properties="ticketflow.notification.max-attempts=2") @ActiveProfiles("demo-h2")
class NotificationDeadLetterTest {
    @Autowired NotificationInbox inbox;@Autowired DeliveryWorker worker;@Autowired NotificationRepo repo;@Autowired ReceiptRepo receipts;@Autowired PlatformTransactionManager manager;
    @Test void exhaustedDeliveryIsRetainedAndCanResumeWithoutLosingIdentity(){
        var c=NotificationCoreTest.fixture();inbox.accept(c);worker.deliver(c.eventId());worker.deliver(c.eventId());worker.deliver(c.eventId());
        assertThat(repo.findById(c.eventId()).orElseThrow().getStatus()).isEqualTo(Notification.Status.DEAD);
        assertThat(receipts.findById(c.eventId())).isEmpty();
        new TransactionTemplate(manager).executeWithoutResult(tx->repo.locked(c.eventId()).orElseThrow().retry(java.time.Instant.now()));
        worker.deliver(c.eventId());
        assertThat(repo.findById(c.eventId()).orElseThrow().getStatus()).isEqualTo(Notification.Status.SENT);
        assertThat(repo.findById(c.eventId()).orElseThrow().getManualRetries()).isEqualTo(1);
        inbox.accept(c);worker.deliver(c.eventId());assertThat(receipts.findById(c.eventId())).isPresent();
    }
}
