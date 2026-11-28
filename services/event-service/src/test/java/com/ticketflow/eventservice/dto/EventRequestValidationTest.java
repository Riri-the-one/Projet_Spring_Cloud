
package com.ticketflow.eventservice.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EventRequestValidationTest {

    @Test
    void capacityMustBePositive() {

        EventRequest request = new EventRequest(
                "Concert TicketFlow",
                "Concert de test",
                "Casablanca",
                Instant.now().plus(30, ChronoUnit.DAYS),
                new BigDecimal("150.00"),
                0
        );

        try (ValidatorFactory factory =
                     Validation.buildDefaultValidatorFactory()) {

            Validator validator = factory.getValidator();

            var violations = validator.validate(request);

            assertThat(violations)
                    .anyMatch(violation ->
                            violation.getPropertyPath()
                                    .toString()
                                    .equals("capacity")
                    );
        }
    }
    @Test
    void negativeCapacityMustBeRejected() {

        EventRequest request = new EventRequest(
                "Concert TicketFlow",
                "Test de capacité négative",
                "Casablanca",
                Instant.now().plus(30, ChronoUnit.DAYS),
                new BigDecimal("150.00"),
                -50
        );

        try (ValidatorFactory factory =
                     Validation.buildDefaultValidatorFactory()) {

            Validator validator = factory.getValidator();

            var violations = validator.validate(request);

            assertThat(violations)
                    .anyMatch(violation ->
                            violation.getPropertyPath()
                                    .toString()
                                    .equals("capacity")
                    );
        }
    }
}
