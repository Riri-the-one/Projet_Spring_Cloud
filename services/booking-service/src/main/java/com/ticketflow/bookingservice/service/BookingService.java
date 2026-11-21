
package com.ticketflow.bookingservice.service;

import com.ticketflow.bookingservice.clients.EventClient;
import com.ticketflow.bookingservice.clients.EventView;
import com.ticketflow.bookingservice.entities.Booking;
import com.ticketflow.bookingservice.entities.BookingStatus;
import com.ticketflow.bookingservice.exceptions.BookingRuleException;
import com.ticketflow.bookingservice.exceptions.ResourceNotFoundException;
import com.ticketflow.bookingservice.exceptions.UpstreamUnavailableException;
import com.ticketflow.bookingservice.repos.BookingRepo;
import com.ticketflow.bookingservice.repos.SeatInventoryRepo;
import feign.FeignException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BookingService {

    private final EventClient events;
    private final BookingRepo bookings;
    private final SeatInventoryRepo inventory;
    private final SeatInventoryInitializer initializer;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BookingService(
            EventClient events,
            BookingRepo bookings,
            SeatInventoryRepo inventory,
            SeatInventoryInitializer initializer,
            TransactionTemplate tx,
            Clock clock) {

        this.events = events;
        this.bookings = bookings;
        this.inventory = inventory;
        this.initializer = initializer;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Creates a booking with a 15-minute payment deadline.
     */
    public Booking book(Long eventId, Long customerId, int quantity) {

        EventView event = fetchEvent(eventId);

        if (!"PUBLISHED".equals(event.status())) {
            throw new BookingRuleException(
                    "The event is not open for booking");
        }

        if (!event.startsAt().isAfter(Instant.now(clock))) {
            throw new BookingRuleException(
                    "The event has already started");
        }

        try {
            initializer.ensureExists(eventId, event.capacity());
        } catch (DataAccessException concurrentCreation) {
            // Another request may have created the counter.
        }

        return tx.execute(status -> {

            if (inventory.reserve(
                    eventId, quantity, event.capacity()) == 0) {

                throw new BookingRuleException(
                        "Not enough seats left for this event");
            }

            Booking booking = new Booking(
                    eventId,
                    event.title(),
                    customerId,
                    quantity,
                    event.price(),
                    Instant.now(clock)
            );

            // Payment must be completed within 15 minutes.
            booking.holdUntil(
                    Instant.now(clock).plusSeconds(900)
            );

            return bookings.save(booking);
        });
    }

    /**
     * Cancels a booking and releases its seats.
     */
    public Booking cancel(Long id) {

        return tx.execute(status -> {

            Booking booking = bookings.findByIdForUpdate(id)
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Booking " + id + " not found"));

            booking.cancel();

            int released = inventory.release(
                    booking.getEventId(),
                    booking.getQuantity()
            );

            if (released != 1) {
                throw new IllegalStateException(
                        "Failed to release seats for booking " + id);
            }

            return bookings.save(booking);
        });
    }

    /**
     * Confirms a booking after successful payment.
     */
    public Booking confirm(Long id) {

        return tx.execute(status -> {

            Booking booking = bookings.findByIdForUpdate(id)
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Booking " + id + " not found"));

            // Already confirmed: do not confirm twice.
            if (booking.getStatus() == BookingStatus.CONFIRMED) {
                return booking;
            }

            // Reject expired payment deadlines.
            if (booking.getExpiresAt() != null
                    && !Instant.now(clock).isBefore(booking.getExpiresAt())) {
                throw new BookingRuleException(
                        "Payment deadline has passed");
            }

            // Only PENDING bookings can be confirmed.
            booking.confirm();

            return bookings.save(booking);
        });
    }

    /**
     * Finds a booking by its ID.
     */
    public Booking get(Long id) {

        return bookings.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Booking " + id + " not found"));
    }

    /**
     * Lists all bookings or bookings for one customer.
     */
    public List<Booking> list(Long customerId) {

        return customerId == null
                ? bookings.findAllByOrderByCreatedAtDescIdDesc()
                : bookings.findByCustomerIdOrderByCreatedAtDescIdDesc(
                customerId);
    }

    /**
     * Fetches an event and handles remote service errors.
     */
    private EventView fetchEvent(Long eventId) {

        try {
            return events.get(eventId);

        } catch (RuntimeException e) {

            Throwable cause =
                    (e instanceof NoFallbackAvailableException
                            && e.getCause() != null)
                            ? e.getCause()
                            : e;

            if (cause instanceof FeignException feign
                    && feign.status() == 404) {

                throw new ResourceNotFoundException(
                        "Event " + eventId + " not found");
            }

            throw new UpstreamUnavailableException(
                    "event service", cause);
        }
    }
}
