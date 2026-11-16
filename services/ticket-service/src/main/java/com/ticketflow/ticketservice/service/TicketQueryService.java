package com.ticketflow.ticketservice.service;

import com.ticketflow.ticketservice.domain.*;
import com.ticketflow.ticketservice.repo.*;
import com.ticketflow.ticketservice.security.CustomerIdentityResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TicketQueryService {
    private final TicketRepo tickets;
    private final TicketBatchRepo batches;
    private final CustomerIdentityResolver identities;
    private final Clock clock;
    public TicketQueryService(TicketRepo tickets, TicketBatchRepo batches, CustomerIdentityResolver identities, Clock clock) {
        this.tickets = tickets; this.batches = batches; this.identities = identities; this.clock = clock;
    }
    public record View(UUID id, long bookingId, String lineId, int seatIndex, long eventId,
            TicketStatus status, Instant issuedAt, Instant validUntil, String controlCode) {}
    public record Validity(boolean valid, String reason, TicketStatus status) {}
    public static class NotFound extends RuntimeException {}
    private View view(Ticket ticket) {
        return new View(ticket.getId(), ticket.getBatch().getBookingId(), ticket.getLineId(), ticket.getSeatIndex(), ticket.getEventId(),
                ticket.statusAt(clock.instant()), ticket.getIssuedAt(), ticket.getValidUntil(), ticket.getControlCode());
    }
    private void requireOwner(TicketBatch batch, CustomerIdentityResolver.Caller caller) {
        if (!caller.admin() && batch.getCustomerId() != identities.customerId(caller)) throw new AccessDeniedException("Ticket access forbidden");
    }
    private Ticket authorized(UUID id) {
        var caller = identities.caller();
        var ticket = tickets.findById(id).orElseThrow(NotFound::new);
        requireOwner(ticket.getBatch(), caller);
        return ticket;
    }
    public View get(UUID id) { return view(authorized(id)); }
    public List<View> mine() {
        long customerId = identities.customerId(identities.caller());
        return tickets.findByBatchCustomerIdOrderByIssuedAtDesc(customerId).stream().map(this::view).toList();
    }
    public List<View> booking(long bookingId) {
        var caller = identities.caller();
        var batch = batches.findById(bookingId).orElseThrow(NotFound::new);
        requireOwner(batch, caller);
        return tickets.findByBatchBookingIdOrderByLineIdAscSeatIndexAsc(bookingId).stream().map(this::view).toList();
    }
    public Validity validity(UUID id, String code) {
        var ticket = authorized(id);
        var status = ticket.statusAt(clock.instant());
        boolean matches = MessageDigest.isEqual(ticket.getControlCode().getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII));
        return new Validity(matches && status == TicketStatus.ISSUED, matches ? status.name() : "CODE_MISMATCH", status);
    }
}
