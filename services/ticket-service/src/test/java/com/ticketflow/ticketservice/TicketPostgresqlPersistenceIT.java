package com.ticketflow.ticketservice;

import static org.assertj.core.api.Assertions.*;
import com.ticketflow.ticketservice.domain.BookingConfirmation;
import com.ticketflow.ticketservice.repo.TicketRepo;
import com.ticketflow.ticketservice.service.TicketIssuanceService;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

/** Two independent JVMs around a PostgreSQL restart. Fixtures never enter the runtime public schema. */
@SpringBootTest
@ActiveProfiles("postgresql")
class TicketPostgresqlPersistenceIT extends TicketJwtSupport {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) { TicketPostgresqlProperties.register(properties); }
    @Autowired TicketIssuanceService issue;
    @Autowired TicketRepo tickets;

    @Test void persistsCodesIdsAndIdempotenceAcrossDatabaseAndApplicationRestart() throws Exception {
        Path marker = Path.of(System.getenv("TICKET_TEST_MARKER"));
        boolean existing = Files.exists(marker);
        Properties fixture = new Properties();
        if (existing) { try (var input = Files.newInputStream(marker)) { fixture.load(input); } }
        else {
            fixture.setProperty("booking", Long.toString(System.currentTimeMillis()));
            fixture.setProperty("message", UUID.randomUUID().toString());
            fixture.setProperty("occurred", Instant.now().minusSeconds(10).toString());
        }
        Instant occurred = Instant.parse(fixture.getProperty("occurred"));
        long booking = Long.parseLong(fixture.getProperty("booking"));
        var event = new BookingConfirmation(UUID.fromString(fixture.getProperty("message")), 1, "booking-service", booking,
                123, 1, "CONFIRMED", "SUCCEEDED", "test-only-payment", occurred,
                List.of(new BookingConfirmation.Line("persistent-test-line", 42, 3, occurred.plusSeconds(86400))));
        var ids = issue.issue(event);
        var rows = tickets.findAllById(ids).stream().sorted(Comparator.comparing(t -> t.getId().toString())).toList();
        String snapshot = rows.stream().map(t -> t.getId() + ":" + t.getControlCode()).collect(java.util.stream.Collectors.joining("|"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (existing) assertThat(hash).isEqualTo(fixture.getProperty("snapshotHash"));
        else {
            fixture.setProperty("snapshotHash", hash);
            try (var output = Files.newOutputStream(marker, StandardOpenOption.CREATE_NEW)) { fixture.store(output, "Test fixture IDs and hash only; no control code or password"); }
        }
        assertThat(issue.issue(event)).containsExactlyElementsOf(ids);
        assertThat(tickets.findByBatchBookingIdOrderByLineIdAscSeatIndexAsc(booking)).hasSize(3);
    }
}
