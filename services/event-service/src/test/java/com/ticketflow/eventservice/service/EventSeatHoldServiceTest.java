package com.ticketflow.eventservice.service;

import com.ticketflow.eventservice.entities.Event;
import com.ticketflow.eventservice.repos.EventRepo;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "ticketflow.security.booking-subject=booking-test"
})
class EventSeatHoldServiceTest {

    @Autowired
    private EventRepo eventRepo;

    @Autowired
    private EventSeatHoldService seatHolds;

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void onlyOneRequestCanHoldTheLastSeat() throws Exception {
        Event event = new Event(
                "Concert de test",
                "Un concert avec une seule place",
                "Rabat",
                Instant.now().plusSeconds(3600),
                new BigDecimal("100.00"),
                1
        );
        event.publish();
        Long eventId = eventRepo.saveAndFlush(event).getId();

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);

        try {
            var first = workers.submit(() -> {
                ready.countDown();
                start.await();
                return seatHolds.hold(eventId, "R42", 1);
            });
            var second = workers.submit(() -> {
                ready.countDown();
                start.await();
                return seatHolds.hold(eventId, "R43", 1);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(new Boolean[]{
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            }).containsExactlyInAnyOrder(true, false);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void repeatingTheSameRequestDoesNotHoldExtraSeats() {
        Event event = new Event(
                "Concert de test",
                "Un concert avec deux places",
                "Rabat",
                Instant.now().plusSeconds(3600),
                new BigDecimal("100.00"),
                2
        );
        event.publish();
        Long eventId = eventRepo.saveAndFlush(event).getId();

        assertThat(seatHolds.hold(eventId, "R42", 1)).isTrue();
        assertThat(seatHolds.hold(eventId, "R42", 1)).isTrue();
        assertThat(seatHolds.hold(eventId, "R43", 1)).isTrue();
        assertThat(seatHolds.hold(eventId, "R44", 1)).isFalse();
    }

    @Test
    void releasingTheSameHoldTwiceDoesNotFreeExtraSeats() {
        Event event = new Event(
                "Concert de test",
                "Un concert avec une seule place",
                "Rabat",
                Instant.now().plusSeconds(3600),
                new BigDecimal("100.00"),
                1
        );
        event.publish();
        Long eventId = eventRepo.saveAndFlush(event).getId();

        assertThat(seatHolds.hold(eventId, "R42", 1)).isTrue();
        assertThat(seatHolds.hold(eventId, "R43", 1)).isFalse();

        seatHolds.release(eventId, "R42");
        assertThat(seatHolds.hold(eventId, "R43", 1)).isTrue();

        seatHolds.release(eventId, "R42");
        assertThat(seatHolds.hold(eventId, "R44", 1)).isFalse();
    }

    @Test
    void bookingCanHoldAndReleaseASeatOverHttp() throws Exception {
        String token = "booking-test-token";
        when(jwtDecoder.decode(token)).thenReturn(Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .claim("aud", java.util.List.of("event-service"))
                .subject("booking-test")
                .build());

        Event event = new Event(
                "Concert de test",
                "Une seule place",
                "Rabat",
                Instant.now().plusSeconds(3600),
                new BigDecimal("100.00"),
                1
        );
        event.publish();
        Long eventId = eventRepo.saveAndFlush(event).getId();

        String url = "http://localhost:" + port
                + "/internal/events/" + eventId + "/seat-holds/";
        String authorization = "Bearer " + token;

        HttpRequest holdR42 = HttpRequest.newBuilder(URI.create(url + "R42"))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"quantity\":1}"))
                .build();

        HttpRequest holdR43 = HttpRequest.newBuilder(URI.create(url + "R43"))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"quantity\":1}"))
                .build();

        HttpRequest releaseR42 = HttpRequest.newBuilder(URI.create(url + "R42"))
                .header("Authorization", authorization)
                .DELETE()
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            var discard = HttpResponse.BodyHandlers.discarding();

            assertThat(client.send(holdR42, discard).statusCode()).isEqualTo(204);
            assertThat(client.send(holdR43, discard).statusCode()).isEqualTo(409);
            assertThat(client.send(releaseR42, discard).statusCode()).isEqualTo(204);
            assertThat(client.send(holdR43, discard).statusCode()).isEqualTo(204);
        }
    }
}