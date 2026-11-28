
package com.ticketflow.eventservice.dto;

import com.ticketflow.eventservice.entities.CategorieEvenement;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public record EventRequest(
        @NotBlank @Size(max = 150) String title,
        @Size(max = 2000) String description,
        @NotBlank String venue,
        @NotNull @Future Instant startsAt,
        @NotNull @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal price,
        @Positive int capacity,
        CategorieEvenement categorie
) {
    // Constructeur pour conserver la compatibilité
    // avec les anciens tests et appels Java.
    public EventRequest(
            String title,
            String description,
            String venue,
            Instant startsAt,
            BigDecimal price,
            int capacity
    ) {
        this(title, description, venue, startsAt, price, capacity, null);
    }
}
