package com.ticketflow.notificationservice.service;
import com.ticketflow.notificationservice.domain.*;
import com.ticketflow.notificationservice.repo.*;
import com.ticketflow.notificationservice.security.CustomerIdentityResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional(readOnly=true)
public class NotificationQueries {
    public static class Missing extends RuntimeException{}
    public static class RetryConflict extends RuntimeException{}
    public record View(UUID eventId,long bookingId,NotificationCommand.Kind kind,Notification.Status status,int attempts,int manualRetries,Instant createdAt,Instant sentAt){}
    private final NotificationRepo repo;private final QuarantineRepo quarantine;private final CustomerIdentityResolver identity;private final Clock clock;
    public NotificationQueries(NotificationRepo repo,QuarantineRepo quarantine,CustomerIdentityResolver identity,Clock clock){this.repo=repo;this.quarantine=quarantine;this.identity=identity;this.clock=clock;}
    View view(Notification n){return new View(n.getEventId(),n.getBookingId(),n.getKind(),n.getStatus(),n.getAttempts(),n.getManualRetries(),n.getCreatedAt(),n.getSentAt());}
    Pageable page(int page){if(page<0)throw new IllegalArgumentException("INVALID_PAGE");return PageRequest.of(page,20,Sort.by("createdAt").descending());}
    public List<View> mine(int page){long id=identity.customerId(identity.caller());return repo.findByCustomerId(id,page(page)).map(this::view).getContent();}
    public View get(UUID id){var caller=identity.caller();var n=repo.findById(id).orElseThrow(Missing::new);if(!caller.admin()&&n.getCustomerId()!=identity.customerId(caller))throw new AccessDeniedException("Notification access forbidden");return view(n);}
    void admin(){if(!identity.caller().admin())throw new AccessDeniedException("Administrator required");}
    public List<View> all(int page){admin();return repo.findAll(page(page)).map(this::view).getContent();}
    public record Quarantined(UUID id,String topic,int partition,long offset,String reason,Instant createdAt){}
    public List<Quarantined> quarantine(int page){admin();return quarantine.findAll(page(page)).map(q->new Quarantined(q.getId(),q.getTopic(),q.getPartition(),q.getOffset(),q.getReason(),q.getCreatedAt())).getContent();}
    @Transactional public View retry(UUID id){admin();var n=repo.locked(id).orElseThrow(Missing::new);if(n.getStatus()!=Notification.Status.DEAD)throw new RetryConflict();n.retry(clock.instant());return view(n);}
}
