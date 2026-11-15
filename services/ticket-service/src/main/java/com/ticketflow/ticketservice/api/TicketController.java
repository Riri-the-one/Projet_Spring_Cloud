package com.ticketflow.ticketservice.api;

import com.ticketflow.ticketservice.service.TicketQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketQueryService query;
    public TicketController(TicketQueryService query) { this.query = query; }
    @GetMapping("/me") public List<TicketQueryService.View> mine() { return query.mine(); }
    @GetMapping("/{id}") public TicketQueryService.View get(@PathVariable UUID id) { return query.get(id); }
    @GetMapping("/bookings/{bookingId}") public List<TicketQueryService.View> booking(@PathVariable @Positive long bookingId) { return query.booking(bookingId); }
    public record Code(@jakarta.validation.constraints.NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String controlCode) {}
    @PostMapping("/{id}/validity") public TicketQueryService.Validity validity(@PathVariable UUID id, @Valid @RequestBody Code code) {
        return query.validity(id, code.controlCode());
    }
}
