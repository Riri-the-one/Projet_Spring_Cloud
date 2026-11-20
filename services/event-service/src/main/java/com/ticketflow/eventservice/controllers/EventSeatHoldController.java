package com.ticketflow.eventservice.controllers;

import com.ticketflow.eventservice.service.EventSeatHoldService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/events/{eventId}/seat-holds")
public class EventSeatHoldController {

    private final EventSeatHoldService seatHolds;

    public EventSeatHoldController(EventSeatHoldService seatHolds) {
        this.seatHolds = seatHolds;
    }

    @PutMapping("/{requestId}")
    public ResponseEntity<Void> hold(
            @PathVariable Long eventId,
            @PathVariable @Size(min = 1, max = 100) String requestId,
            @Valid @RequestBody HoldRequest request) {
        boolean accepted = seatHolds.hold(eventId, requestId, request.quantity());

        if (accepted) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(409).build();
    }

    @DeleteMapping("/{requestId}")
    public ResponseEntity<Void> release(
            @PathVariable Long eventId,
            @PathVariable @Size(min = 1, max = 100) String requestId) {
        seatHolds.release(eventId, requestId);
        return ResponseEntity.noContent().build();
    }

    public record HoldRequest(@NotNull @Positive Integer quantity) {}
}