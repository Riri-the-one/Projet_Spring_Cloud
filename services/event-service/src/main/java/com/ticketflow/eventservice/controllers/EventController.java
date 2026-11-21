package com.ticketflow.eventservice.controllers;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import com.ticketflow.eventservice.dto.EventRequest;
import com.ticketflow.eventservice.dto.EventResponse;
import com.ticketflow.eventservice.service.EventService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<EventResponse> create(
            @Valid @RequestBody EventRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String issuer = jwt == null ? null : jwt.getClaimAsString("iss");
        String subject = jwt == null ? null : jwt.getSubject();

        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        EventResponse created = EventResponse.from(service.create(request, issuer, subject));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    EventResponse get(@PathVariable Long id) {
        return EventResponse.from(service.getPublished(id));
    }

    /** Public catalog: PUBLISHED events only. */
    @GetMapping
    List<EventResponse> list(@RequestParam(required = false) String venue) {
        return service.listPublished(venue).stream().map(EventResponse::from).toList();
    }

    @PutMapping("/{id}")
    EventResponse update(
            @PathVariable Long id,
            @Valid @RequestBody EventRequest request,
            JwtAuthenticationToken authentication
    ) {
        Jwt jwt = authentication.getToken();
        return EventResponse.from(service.update(
                id, request, jwt.getClaimAsString("iss"), jwt.getSubject(), isAdmin(authentication)
        ));
    }

    @PostMapping("/{id}/publish")
    EventResponse publish(@PathVariable Long id, JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        return EventResponse.from(service.publish(
                id, jwt.getClaimAsString("iss"), jwt.getSubject(), isAdmin(authentication)
        ));
    }

    @PostMapping("/{id}/close")
    EventResponse close(@PathVariable Long id, JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        return EventResponse.from(service.close(
                id, jwt.getClaimAsString("iss"), jwt.getSubject(), isAdmin(authentication)
        ));
    }

    private boolean isAdmin(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
