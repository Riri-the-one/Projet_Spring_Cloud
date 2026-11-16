package com.ticketflow.notificationservice.api;
import com.ticketflow.notificationservice.service.NotificationQueries;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @Validated @RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationQueries queries;
    public NotificationController(NotificationQueries queries){this.queries=queries;}
    @GetMapping("/me") public List<NotificationQueries.View> mine(@RequestParam(defaultValue="0") @PositiveOrZero int page){return queries.mine(page);}
    @GetMapping("/{id}") public NotificationQueries.View get(@PathVariable UUID id){return queries.get(id);}
    @GetMapping public List<NotificationQueries.View> all(@RequestParam(defaultValue="0") @PositiveOrZero int page){return queries.all(page);}
    @GetMapping("/quarantine") public List<NotificationQueries.Quarantined> quarantine(@RequestParam(defaultValue="0") @PositiveOrZero int page){return queries.quarantine(page);}
    @PostMapping("/{id}/retry") public NotificationQueries.View retry(@PathVariable UUID id){return queries.retry(id);}
}
