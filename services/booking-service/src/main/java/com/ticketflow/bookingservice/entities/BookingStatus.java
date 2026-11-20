package com.ticketflow.bookingservice.entities;

/**
 * PENDING: seats are held, waiting for the payment. CONFIRMED: paid.
 * CANCELLED: seats released by a cancellation. EXPIRED: payment deadline passed, seats released.
 */
public enum BookingStatus {
    PENDING, CONFIRMED, CANCELLED, EXPIRED;

    /** Does a booking in this status hold seats? */
    public boolean holdsSeats() {
        return this != CANCELLED && this != EXPIRED;
    }
}