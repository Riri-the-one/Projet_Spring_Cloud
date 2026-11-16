package com.ticketflow.notificationservice.config;
import com.ticketflow.notificationservice.service.DeliveryWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @ConditionalOnProperty(name="ticketflow.notification.delivery-enabled",havingValue="true",matchIfMissing=true)
public class DeliverySchedule {
    private final DeliveryWorker worker;
    public DeliverySchedule(DeliveryWorker worker){this.worker=worker;}
    @Scheduled(fixedDelayString="${ticketflow.notification.poll-ms:1000}") public void run(){worker.due();}
}
