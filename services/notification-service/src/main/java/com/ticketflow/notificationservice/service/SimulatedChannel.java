package com.ticketflow.notificationservice.service;
import com.ticketflow.notificationservice.domain.*;
import com.ticketflow.notificationservice.repo.ReceiptRepo;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** Only a durable local receipt: no email, SMS, remote API or Booking update. */
@Component
public class SimulatedChannel {
    public static class Unavailable extends RuntimeException{}
    private final ReceiptRepo receipts; private final Clock clock; private final int failFirst;
    public SimulatedChannel(ReceiptRepo receipts,Clock clock,@Value("${ticketflow.notification.simulated-fail-first:0}") int failFirst){this.receipts=receipts;this.clock=clock;this.failFirst=failFirst;}
    public void send(Notification n){
        if(n.getAttempts()<=failFirst)throw new Unavailable();
        if(!receipts.existsById(n.getEventId()))receipts.saveAndFlush(new SimulatedReceipt(n.getEventId(),clock.instant()));
    }
}
