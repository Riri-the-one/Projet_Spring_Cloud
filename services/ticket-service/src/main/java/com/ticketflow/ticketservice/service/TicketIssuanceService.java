package com.ticketflow.ticketservice.service;

import com.ticketflow.ticketservice.domain.*;
import com.ticketflow.ticketservice.repo.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** No network ingress calls this service. A future trusted Booking adapter must validate its contract first. */
@Service
public class TicketIssuanceService {
    private final TicketBatchRepo batches;
    private final TicketRepo tickets;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public TicketIssuanceService(TicketBatchRepo batches, TicketRepo tickets, PlatformTransactionManager manager, Clock clock) {
        this.batches = batches; this.tickets = tickets; this.transaction = new TransactionTemplate(manager); this.clock = clock;
        // A uniqueness failure must roll back completely before the losing caller reads the winner.
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public List<UUID> issue(BookingConfirmation confirmation) {
        validate(confirmation);
        String fingerprint = fingerprint(confirmation);
        try {
            return transaction.execute(status -> {
                var existing = batches.findById(confirmation.bookingId());
                if (existing.isPresent()) return replay(existing.get(), fingerprint);
                var batch = batches.saveAndFlush(new TicketBatch(confirmation.bookingId(), confirmation.customerId(), confirmation.messageId(), fingerprint));
                List<Ticket> created = new ArrayList<>();
                for (var line : confirmation.lines()) {
                    for (int seat = 1; seat <= line.quantity(); seat++) {
                        byte[] bytes = new byte[32]; random.nextBytes(bytes);
                        created.add(new Ticket(batch, line.lineId(), seat, line.eventId(),
                                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), clock.instant(), line.validUntil()));
                    }
                }
                tickets.saveAllAndFlush(created);
                return tickets.findByBatchBookingIdOrderByLineIdAscSeatIndexAsc(confirmation.bookingId()).stream().map(Ticket::getId).toList();
            });
        } catch (DataIntegrityViolationException duplicate) {
            return transaction.execute(status -> batches.findById(confirmation.bookingId())
                    .map(batch -> replay(batch, fingerprint)).orElseThrow(ConfirmationConflictException::new));
        }
    }
    private List<UUID> replay(TicketBatch batch, String fingerprint) {
        if (!batch.getFingerprint().equals(fingerprint)) throw new ConfirmationConflictException();
        return tickets.findByBatchBookingIdOrderByLineIdAscSeatIndexAsc(batch.getBookingId()).stream().map(Ticket::getId).toList();
    }
    private void validate(BookingConfirmation c) {
        if (c == null || c.messageId() == null || c.schemaVersion() != 1 || !"booking-service".equals(c.source())
                || c.bookingId() < 1 || c.customerId() < 1 || c.bookingVersion() < 1 || !"CONFIRMED".equals(c.status())
                || !"SUCCEEDED".equals(c.paymentStatus()) || c.paymentId() == null || c.paymentId().isBlank() || c.paymentId().length() > 128
                || c.occurredAt() == null || c.occurredAt().isAfter(clock.instant().plusSeconds(30))
                || c.lines() == null || c.lines().isEmpty() || c.lines().size() > 100) {
            throw new IllegalArgumentException("An admissible confirmed booking is required");
        }
        Set<String> identifiers = new HashSet<>();
        long total = 0;
        for (var line : c.lines()) {
            if (line == null || line.lineId() == null || line.lineId().isBlank() || line.lineId().length() > 64
                    || !identifiers.add(line.lineId()) || line.eventId() < 1 || line.quantity() < 1 || line.quantity() > 1000
                    || line.validUntil() == null || !line.validUntil().isAfter(c.occurredAt())) {
                throw new IllegalArgumentException("Invalid confirmation lines");
            }
            total += line.quantity();
        }
        if (total > 1000) throw new IllegalArgumentException("Confirmation exceeds the local issuance limit");
    }
    private String fingerprint(BookingConfirmation c) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeLong(c.bookingId()); out.writeLong(c.customerId()); out.writeLong(c.bookingVersion());
                out.writeUTF(c.paymentId()); out.writeUTF(c.occurredAt().toString());
                for (var line : c.lines().stream().sorted(Comparator.comparing(BookingConfirmation.Line::lineId)).toList()) {
                    out.writeUTF(line.lineId()); out.writeLong(line.eventId()); out.writeInt(line.quantity()); out.writeUTF(line.validUntil().toString());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("Cannot fingerprint confirmation"); }
    }
}
