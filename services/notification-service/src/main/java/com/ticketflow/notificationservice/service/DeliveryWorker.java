package com.ticketflow.notificationservice.service;
import com.ticketflow.notificationservice.domain.Notification;
import com.ticketflow.notificationservice.repo.NotificationRepo;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class DeliveryWorker {
    private final NotificationRepo repo;private final SimulatedChannel channel;private final Clock clock;private final TransactionTemplate tx;private final int maximum;private final long backoff;
    public DeliveryWorker(NotificationRepo repo,SimulatedChannel channel,Clock clock,PlatformTransactionManager manager,
        @Value("${ticketflow.notification.max-attempts:3}") int maximum,@Value("${ticketflow.notification.backoff-ms:1000}") long backoff){
        if(maximum<1||maximum>10||backoff<0||backoff>3600000)throw new IllegalArgumentException("Invalid bounded delivery policy");
        this.repo=repo;this.channel=channel;this.clock=clock;this.tx=new TransactionTemplate(manager);this.maximum=maximum;this.backoff=backoff;
    }
    public void due(){
        var ids=tx.execute(s->repo.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAt(List.of(Notification.Status.PENDING,Notification.Status.RETRY),clock.instant(),PageRequest.of(0,20)).stream().map(Notification::getEventId).toList());
        for(var id:ids)deliver(id);
    }
    public void deliver(UUID id){tx.executeWithoutResult(status->{
        var n=repo.locked(id).orElseThrow();
        if(n.getStatus()==Notification.Status.SENT||n.getStatus()==Notification.Status.DEAD||n.getNextAttemptAt().isAfter(clock.instant()))return;
        n.attempt();
        try{channel.send(n);n.sent(clock.instant());}catch(SimulatedChannel.Unavailable ex){n.failed(clock.instant(),maximum,backoff);}
    });}
}
