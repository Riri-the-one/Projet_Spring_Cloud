package com.ticketflow.notificationservice.service;
import com.ticketflow.notificationservice.domain.*;
import com.ticketflow.notificationservice.repo.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class NotificationInbox {
    public static class Conflict extends RuntimeException {public Conflict(){super("EVENT_ID_CONFLICT");}}
    private final NotificationRepo repo; private final TransactionTemplate tx; private final Clock clock;
    public NotificationInbox(NotificationRepo repo,PlatformTransactionManager manager,Clock clock){this.repo=repo;this.tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);this.clock=clock;}
    public UUID accept(NotificationCommand command){
        try{return tx.execute(status->{
            var existing=repo.findById(command.eventId()); if(existing.isPresent()){verify(existing.get(),command);return command.eventId();}
            repo.saveAndFlush(new Notification(command,clock.instant())); return command.eventId();
        });}catch(DataIntegrityViolationException collision){return tx.execute(status->{verify(repo.findById(command.eventId()).orElseThrow(()->collision),command);return command.eventId();});}
    }
    private void verify(Notification n,NotificationCommand c){if(!n.same(c))throw new Conflict();}
}
