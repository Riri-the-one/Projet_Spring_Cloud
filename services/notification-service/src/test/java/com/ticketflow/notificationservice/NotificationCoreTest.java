package com.ticketflow.notificationservice;
import static org.assertj.core.api.Assertions.*;
import com.ticketflow.notificationservice.domain.*;
import com.ticketflow.notificationservice.repo.*;
import com.ticketflow.notificationservice.service.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
@SpringBootTest @ActiveProfiles("demo-h2")
class NotificationCoreTest {
    @Autowired NotificationInbox inbox;@Autowired DeliveryWorker worker;@Autowired NotificationRepo notifications;@Autowired ReceiptRepo receipts;
    static NotificationCommand fixture(){return new NotificationCommand(UUID.randomUUID(),123,456,NotificationCommand.Kind.CONFIRMATION,Instant.now());}
    @ParameterizedTest @EnumSource(NotificationCommand.Kind.class)
    void boundedRetriesRecoverTemporaryChannelFailure(NotificationCommand.Kind kind){
        var c=new NotificationCommand(UUID.randomUUID(),123,456,kind,Instant.now());inbox.accept(c);
        worker.deliver(c.eventId());assertThat(notifications.findById(c.eventId()).orElseThrow().getStatus()).isEqualTo(Notification.Status.RETRY);
        worker.deliver(c.eventId());worker.deliver(c.eventId());worker.deliver(c.eventId());
        var n=notifications.findById(c.eventId()).orElseThrow();assertThat(n.getStatus()).isEqualTo(Notification.Status.SENT);assertThat(n.getAttempts()).isEqualTo(3);
        assertThat(receipts.findById(c.eventId())).isPresent();
    }
    @Test void replayDoesNotCreateAnotherDeliveryAndConflictingPayloadCannotChangeOwner(){
        var c=fixture();inbox.accept(c);worker.deliver(c.eventId());worker.deliver(c.eventId());worker.deliver(c.eventId());
        inbox.accept(c);worker.deliver(c.eventId());assertThat(notifications.findById(c.eventId()).orElseThrow().getAttempts()).isEqualTo(3);
        assertThatThrownBy(()->inbox.accept(new NotificationCommand(c.eventId(),c.bookingId(),999,c.kind(),c.occurredAt()))).isInstanceOf(NotificationInbox.Conflict.class);
        assertThat(notifications.findById(c.eventId()).orElseThrow().getCustomerId()).isEqualTo(456);
    }
    @Test void concurrentInboxAndWorkersCannotDuplicateNotificationOrReceipt()throws Exception{
        var c=fixture();var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<UUID> insert=()->{barrier.await(10,TimeUnit.SECONDS);return inbox.accept(c);};
            var a=pool.submit(insert);var b=pool.submit(insert);assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
            worker.deliver(c.eventId());worker.deliver(c.eventId());
            var one=pool.submit(()->worker.deliver(c.eventId()));var two=pool.submit(()->worker.deliver(c.eventId()));one.get(20,TimeUnit.SECONDS);two.get(20,TimeUnit.SECONDS);
        }
        assertThat(notifications.findById(c.eventId()).orElseThrow().getAttempts()).isEqualTo(3);
        assertThat(receipts.findById(c.eventId())).isPresent();
    }
    @Test void invalidIdentityOrBookingCannotEnterInbox(){
        long before=notifications.count();assertThatThrownBy(()->new NotificationCommand(UUID.randomUUID(),1,0,NotificationCommand.Kind.FAILURE,Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(notifications.count()).isEqualTo(before);
    }
}
