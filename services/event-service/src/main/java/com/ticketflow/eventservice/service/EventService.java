package com.ticketflow.eventservice.service;

import com.ticketflow.eventservice.dto.EventRequest;
import com.ticketflow.eventservice.entities.Event;
import com.ticketflow.eventservice.entities.EventStatus;
import com.ticketflow.eventservice.exceptions.EventNotFoundException;
import com.ticketflow.eventservice.repos.EventRepo;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;

@Service
@Transactional
public class EventService {

    private final EventRepo repo;

    public EventService(EventRepo repo) {
        this.repo = repo;
    }


    public Event create(EventRequest request) {
        return create(request, null, null);
    }

    public Event create(EventRequest request, String ownerIssuer, String ownerSubject) {
        Event event = new Event(
                request.title(),
                request.description(),
                request.venue(),
                request.startsAt(),
                request.price(),
                request.capacity(),
                ownerIssuer,
                ownerSubject
        );

        event.changerCategorie(request.categorie());
        return repo.save(event);
    }


    @Transactional(readOnly = true)
    public Event get(Long id) {
        return repo.findById(id).orElseThrow(() -> new EventNotFoundException(id));
    }
    @Transactional(readOnly = true)
    public Event getPublished(Long id) {
        Event event = get(id);
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new EventNotFoundException(id);
        }
        return event;
    }
    /** The public catalog: only PUBLISHED events, optionally filtered by venue. */
    @Transactional(readOnly = true)
    public List<Event> listPublished(String venue) {
        if (venue == null || venue.isBlank()) {
            return repo.findByStatusOrderByStartsAtAsc(EventStatus.PUBLISHED);
        }
        return repo.findByStatusAndVenueIgnoreCaseOrderByStartsAtAsc(EventStatus.PUBLISHED, venue.trim());
    }

    private Event getManageableEvent(Long id, String issuer, String subject, boolean admin) {
        Event event = get(id);

        boolean owner = issuer != null && subject != null
                && issuer.equals(event.getOwnerIssuer())
                && subject.equals(event.getOwnerSubject());

        if (!admin && !owner) {
            throw new AccessDeniedException("Vous ne pouvez pas gérer cet événement.");
        }

        return event;
    }
    public Event update(Long id, EventRequest request, String issuer, String subject, boolean admin) {
        Event event = getManageableEvent(id, issuer, subject, admin);

        event.update(
                request.title(),
                request.description(),
                request.venue(),
                request.startsAt(),
                request.price(),
                request.capacity()
        );

        event.changerCategorie(request.categorie());

        return event;
    }


    public Event publish(Long id, String issuer, String subject, boolean admin) {
        Event event = getManageableEvent(id, issuer, subject, admin);
        event.publish();
        return event;
    }

    public Event close(Long id, String issuer, String subject, boolean admin) {
        Event event = getManageableEvent(id, issuer, subject, admin);
        event.close();
        return event;
    }
}
