package com.ticketflow.eventservice.service;

import com.ticketflow.eventservice.entities.Event;
import com.ticketflow.eventservice.entities.EventStatus;
import com.ticketflow.eventservice.exceptions.EventNotFoundException;
import com.ticketflow.eventservice.repos.EventRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventSeatHoldService {

    private final EventRepo eventRepo;
    private final JdbcTemplate jdbc;

    public EventSeatHoldService(EventRepo eventRepo, JdbcTemplate jdbc) {
        this.eventRepo = eventRepo;
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean hold(Long eventId, String requestId, int quantity) {
        if (quantity <= 0 || requestId == null || requestId.isBlank()
                || requestId.length() > 100) {
            throw new IllegalArgumentException("Demande de blocage invalide");
        }

        Event event = eventRepo.findByIdForUpdate(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));

        var existing = jdbc.query("""
                SELECT quantity, status
                FROM event_seat_holds
                WHERE event_id = ? AND request_id = ?
                """, (row, index) -> new ExistingHold(
                        row.getInt("quantity"), row.getString("status")),
                eventId, requestId);

        if (!existing.isEmpty()) {
            ExistingHold hold = existing.getFirst();
            return hold.quantity() == quantity && "ACTIVE".equals(hold.status());
        }

        if (event.getStatus() != EventStatus.PUBLISHED) {
            return false;
        }

        Long heldSeats = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0)
                FROM event_seat_holds
                WHERE event_id = ? AND status = 'ACTIVE'
                """, Long.class, eventId);

        if (quantity > event.getCapacity() - heldSeats) {
            return false;
        }

        jdbc.update("""
                INSERT INTO event_seat_holds (event_id, request_id, quantity, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, eventId, requestId, quantity);

        return true;
    }
    @Transactional
    public void release(Long eventId, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 100) {
            throw new IllegalArgumentException("Identifiant de demande invalide");
        }

        eventRepo.findByIdForUpdate(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));

        jdbc.update("""
            UPDATE event_seat_holds
            SET status = 'RELEASED'
            WHERE event_id = ? AND request_id = ? AND status = 'ACTIVE'
            """, eventId, requestId);
    }
    private record ExistingHold(int quantity, String status) {}
}