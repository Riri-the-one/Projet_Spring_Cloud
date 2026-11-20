
package com.ticketflow.bookingservice.service;

import com.ticketflow.bookingservice.entities.Booking;
import com.ticketflow.bookingservice.entities.BookingStatus;
import com.ticketflow.bookingservice.repos.BookingRepo;
import com.ticketflow.bookingservice.repos.SeatInventoryRepo;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

@Service
public class BookingExpirationService {

    private final BookingRepo bookings;
    private final SeatInventoryRepo inventory;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BookingExpirationService(
            BookingRepo bookings,
            SeatInventoryRepo inventory,
            TransactionTemplate tx,
            Clock clock) {
        this.bookings = bookings;
        this.inventory = inventory;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60000)
    public void expireBookings() {
        Instant now = Instant.now(clock);

        for (Booking booking : bookings.findByStatusAndExpiresAtBefore(
                BookingStatus.PENDING, now)) {

            tx.executeWithoutResult(status -> {

                Booking current = bookings.findByIdForUpdate(booking.getId())
                        .orElse(null);

                if (current == null
                        || current.getStatus() != BookingStatus.PENDING
                        || current.getExpiresAt() == null
                        || current.getExpiresAt().isAfter(Instant.now(clock))) {
                    return;
                }

                current.expire();

                int released = inventory.release(
                        current.getEventId(),
                        current.getQuantity()
                );

                if (released != 1) {
                    throw new IllegalStateException(
                            "Failed to release seats for booking " + current.getId()
                    );
                }

                bookings.save(current);
            });
        }
    }
}
