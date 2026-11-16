package com.ticketflow.notificationservice;
import static org.assertj.core.api.Assertions.*;
import com.ticketflow.notificationservice.repo.ReceiptRepo;
import com.ticketflow.notificationservice.service.NotificationInbox;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:notification_scheduler","ticketflow.notification.delivery-enabled=true","ticketflow.notification.poll-ms=20","ticketflow.notification.backoff-ms=10"})
@ActiveProfiles("demo-h2") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationSchedulerTest {
    @Autowired NotificationInbox inbox;@Autowired ReceiptRepo receipts;
    @Test void schedulerRecoversTemporaryFailureWithoutManualDeliveryCalls()throws Exception{
        var c=NotificationCoreTest.fixture();inbox.accept(c);long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
        while(!receipts.existsById(c.eventId())&&System.nanoTime()<deadline)Thread.sleep(30);
        assertThat(receipts.existsById(c.eventId())).isTrue();
    }
}
