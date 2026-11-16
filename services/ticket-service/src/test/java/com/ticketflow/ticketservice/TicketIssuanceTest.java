package com.ticketflow.ticketservice;

import static org.assertj.core.api.Assertions.*;
import com.ticketflow.ticketservice.domain.*;
import com.ticketflow.ticketservice.repo.*;
import com.ticketflow.ticketservice.service.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("demo-h2")
class TicketIssuanceTest extends TicketJwtSupport {
    static final AtomicLong IDS = new AtomicLong(100000);
    @Autowired TicketIssuanceService issue;
    @Autowired TicketRepo tickets;
    @Autowired TicketBatchRepo batches;
    @Autowired PlatformTransactionManager manager;
    static BookingConfirmation confirmation(long booking, long customer, int quantity) {
        Instant at = Instant.now().minusSeconds(10);
        return new BookingConfirmation(UUID.randomUUID(), 1, "booking-service", booking, customer, 1,
                "CONFIRMED", "SUCCEEDED", "test-payment", at,
                List.of(new BookingConfirmation.Line("line-1", 42, quantity, at.plusSeconds(3600))));
    }
    BookingConfirmation copy(BookingConfirmation c, UUID message, long customer, String state, String payment, List<BookingConfirmation.Line> lines) {
        return new BookingConfirmation(message, c.schemaVersion(), c.source(), c.bookingId(), customer, c.bookingVersion(), state,
                payment, c.paymentId(), c.occurredAt(), lines);
    }
    @Test void emitsOneDistinctTicketPerSeatWithOpaqueRandomCodes() {
        var event = confirmation(IDS.incrementAndGet(), 123, 30);
        var ids = issue.issue(event);
        assertThat(ids).hasSize(30).doesNotHaveDuplicates();
        var rows = tickets.findAllById(ids);
        assertThat(rows).extracting(Ticket::getControlCode).doesNotHaveDuplicates();
        rows.forEach(ticket -> {
            assertThat(ticket.getControlCode()).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(ticket.getControlCode())).hasSize(32);
            assertThat(ticket.getId().version()).isEqualTo(4);
            assertThat(ticket.statusAt(Instant.now())).isEqualTo(TicketStatus.ISSUED);
        });
    }
    @ParameterizedTest @CsvSource({"PENDING,SUCCEEDED", "CONFIRMED,PENDING", "CONFIRMED,FAILED", "CANCELLED,SUCCEEDED"})
    void rejectsNonAdmissibleConfirmationBeforeAnyWrite(String state, String payment) {
        var event = confirmation(IDS.incrementAndGet(), 123, 2);
        assertThatThrownBy(() -> issue.issue(copy(event, event.messageId(), 123, state, payment, event.lines())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(batches.existsById(event.bookingId())).isFalse();
    }
    @Test void rejectsInvalidQuantityAndSourceAndVersion() {
        var invalid = confirmation(IDS.incrementAndGet(), 123, 0);
        assertThatThrownBy(() -> issue.issue(invalid)).isInstanceOf(IllegalArgumentException.class);
        var valid = confirmation(invalid.bookingId(), 123, 1);
        assertThatThrownBy(() -> issue.issue(new BookingConfirmation(valid.messageId(), 2, "untrusted", valid.bookingId(), 123, 1,
                "CONFIRMED", "SUCCEEDED", valid.paymentId(), valid.occurredAt(), valid.lines())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(batches.existsById(valid.bookingId())).isFalse();
    }
    @Test void replayReturnsOriginalIdsEvenWithANewMessageIdAndCanonicalLineOrder() {
        var original = confirmation(IDS.incrementAndGet(), 123, 2);
        var first = issue.issue(original);
        assertThat(issue.issue(original)).containsExactlyElementsOf(first);
        assertThat(issue.issue(copy(original, UUID.randomUUID(), 123, "CONFIRMED", "SUCCEEDED", original.lines())))
                .containsExactlyElementsOf(first);
        assertThat(tickets.findByBatchBookingIdOrderByLineIdAscSeatIndexAsc(original.bookingId())).hasSize(2);
    }
    @Test void supportsSeveralLinesAndCanonicalReplay() {
        var c = confirmation(IDS.incrementAndGet(), 123, 2);
        var second = new BookingConfirmation.Line("line-2", 84, 3, c.lines().getFirst().validUntil());
        var event = copy(c, c.messageId(), 123, "CONFIRMED", "SUCCEEDED", List.of(c.lines().getFirst(), second));
        var ids = issue.issue(event);
        assertThat(ids).hasSize(5);
        assertThat(issue.issue(copy(event, event.messageId(), 123, "CONFIRMED", "SUCCEEDED", List.of(second, c.lines().getFirst()))))
                .containsExactlyElementsOf(ids);
    }
    @Test void conflictingCustomerOrQuantityNeverChangesExistingIssuance() {
        var c = confirmation(IDS.incrementAndGet(), 123, 2);
        var ids = issue.issue(c);
        assertThatThrownBy(() -> issue.issue(copy(c, UUID.randomUUID(), 999, "CONFIRMED", "SUCCEEDED", c.lines())))
                .isInstanceOf(ConfirmationConflictException.class);
        var line = c.lines().getFirst();
        assertThatThrownBy(() -> issue.issue(copy(c, UUID.randomUUID(), 123, "CONFIRMED", "SUCCEEDED",
                List.of(new BookingConfirmation.Line(line.lineId(), line.eventId(), 3, line.validUntil())))))
                .isInstanceOf(ConfirmationConflictException.class);
        assertThat(issue.issue(c)).containsExactlyElementsOf(ids);
    }
    @Test void originalMessageCannotBeReusedForAnotherBookingAndRollbackAllowsLaterIssuance() {
        var c = confirmation(IDS.incrementAndGet(), 123, 2);
        issue.issue(c);
        var another = confirmation(IDS.incrementAndGet(), 123, 2);
        assertThatThrownBy(() -> issue.issue(copy(another, c.messageId(), 123, "CONFIRMED", "SUCCEEDED", another.lines())))
                .isInstanceOf(ConfirmationConflictException.class);
        assertThat(batches.existsById(another.bookingId())).isFalse();
        assertThat(issue.issue(another)).hasSize(2);
    }
    @Test void concurrentIssuanceProducesExactlyOneBatchAndOneTicketPerSeat() throws Exception {
        var c = confirmation(IDS.incrementAndGet(), 123, 5);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<List<UUID>> call = () -> { barrier.await(10, TimeUnit.SECONDS); return issue.issue(c); };
            var one = executor.submit(call); var two = executor.submit(call);
            assertThat(one.get(20, TimeUnit.SECONDS)).containsExactlyElementsOf(two.get(20, TimeUnit.SECONDS));
        }
        assertThat(tickets.findByBatchBookingIdOrderByLineIdAscSeatIndexAsc(c.bookingId())).hasSize(5);
    }
    @Test void statusAndExpiryPreventReusingAnInvalidTicket() {
        var c = confirmation(IDS.incrementAndGet(), 123, 1);
        UUID id = issue.issue(c).getFirst();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var ticket = tickets.findById(id).orElseThrow();
            assertThat(ticket.statusAt(ticket.getValidUntil())).isEqualTo(TicketStatus.EXPIRED);
            assertThatThrownBy(() -> ticket.markUsed(ticket.getValidUntil())).isInstanceOf(IllegalStateException.class);
            ticket.markUsed(Instant.now());
            assertThat(ticket.statusAt(Instant.now())).isEqualTo(TicketStatus.USED);
            assertThatThrownBy(() -> ticket.markUsed(Instant.now())).isInstanceOf(IllegalStateException.class);
            ticket.voidTicket();
            assertThat(ticket.statusAt(Instant.now())).isEqualTo(TicketStatus.VOIDED);
        });
    }
}
