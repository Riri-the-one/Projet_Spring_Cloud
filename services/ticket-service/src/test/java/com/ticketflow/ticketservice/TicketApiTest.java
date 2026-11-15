package com.ticketflow.ticketservice;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.ticketflow.ticketservice.service.*;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo-h2")
class TicketApiTest extends TicketJwtSupport {
    static final HttpServer CUSTOMER;
    static final AtomicReference<String> FORWARDED = new AtomicReference<>();
    static final AtomicReference<String> SPOOFED = new AtomicReference<>();
    static {
        try {
            CUSTOMER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CUSTOMER.createContext("/internal/customers/me", exchange -> {
                int status = 503; String body = "{}";
                try {
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    FORWARDED.set(authorization); SPOOFED.set(exchange.getRequestHeaders().getFirst("X-Customer-Id"));
                    String subject = SignedJWT.parse(authorization.substring(7)).getJWTClaimsSet().getSubject();
                    if (subject.equals("owner")) { status = 200; body = "{\"customerId\":123}"; }
                    else if (subject.equals("other")) { status = 200; body = "{\"customerId\":456}"; }
                    else if (subject.equals("missing")) { status = 404; }
                    else if (subject.equals("redirect")) { status = 302; exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/"); }
                } catch (Exception ignored) { /* Reject malformed test calls. */ }
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            CUSTOMER.start(); Runtime.getRuntime().addShutdownHook(new Thread(() -> CUSTOMER.stop(0)));
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void customer(DynamicPropertyRegistry properties) {
        properties.add("ticketflow.customer-url", () -> "http://127.0.0.1:" + CUSTOMER.getAddress().getPort());
    }
    @Value("${local.server.port}") int port;
    @Autowired TicketIssuanceService issue;
    @Autowired TicketQueryService query;
    HttpResponse<String> request(String method, String path, String jwt, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(15))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (jwt != null) builder.header("Authorization", "Bearer " + jwt);
        if (body != null) builder.header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    @ParameterizedTest @ValueSource(strings = {"absent", "expired", "signature", "issuer", "audience", "subject", "algorithm", "nbf"})
    void rejectsInvalidTokens(String scenario) throws Exception {
        String bearer = switch (scenario) {
            case "absent" -> null;
            case "expired" -> token(KEY, JWSAlgorithm.RS256, c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120))), "CUSTOMER");
            case "signature" -> token(OTHER_KEY, JWSAlgorithm.RS256, c -> {}, "CUSTOMER");
            case "issuer" -> token(KEY, JWSAlgorithm.RS256, c -> c.issuer("https://wrong.test"), "CUSTOMER");
            case "audience" -> token(KEY, JWSAlgorithm.RS256, c -> c.audience("wrong"), "CUSTOMER");
            case "subject" -> token(KEY, JWSAlgorithm.RS256, c -> c.subject(null), "CUSTOMER");
            case "nbf" -> token(KEY, JWSAlgorithm.RS256, c -> c.notBeforeTime(Date.from(Instant.now().plusSeconds(120))), "CUSTOMER");
            default -> token(KEY, JWSAlgorithm.RS512, c -> {}, "CUSTOMER");
        };
        assertThat(request("GET", "/api/tickets/me", bearer, null).statusCode()).isEqualTo(401);
    }
    @Test void ownerCanReadAndValidateTicketsUsingServerResolvedProfile() throws Exception {
        var c = TicketIssuanceTest.confirmation(TicketIssuanceTest.IDS.incrementAndGet(), 123, 2);
        var ids = issue.issue(c); String owner = tokenFor("owner", "CUSTOMER");
        var detail = request("GET", "/api/tickets/" + ids.getFirst(), owner, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("\"bookingId\":" + c.bookingId());
        String code = com.jayway.jsonpath.JsonPath.read(detail.body(), "$.controlCode");
        var valid = request("POST", "/api/tickets/" + ids.getFirst() + "/validity", owner, "{\"controlCode\":\"" + code + "\"}");
        assertThat(valid.statusCode()).isEqualTo(200); assertThat(valid.body()).contains("\"valid\":true");
        var invalid = request("POST", "/api/tickets/" + ids.getFirst() + "/validity", owner, "{\"controlCode\":\"" + "A".repeat(43) + "\"}");
        assertThat(invalid.statusCode()).isEqualTo(200); assertThat(invalid.body()).contains("CODE_MISMATCH");
        assertThat(request("GET", "/api/tickets/bookings/" + c.bookingId(), owner, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/tickets/me", owner, null, "X-Customer-Id", "456").body()).contains(ids.getFirst().toString());
        assertThat(FORWARDED.get()).isEqualTo("Bearer " + owner); assertThat(SPOOFED.get()).isNull();
    }
    @Test void anotherCustomerCannotReadListByBookingOrVerifyTicketsEvenWithSpoofedHeaders() throws Exception {
        var c = TicketIssuanceTest.confirmation(TicketIssuanceTest.IDS.incrementAndGet(), 123, 1);
        UUID id = issue.issue(c).getFirst(); String other = tokenFor("other", "CUSTOMER");
        assertThat(request("GET", "/api/tickets/" + id, other, null, "X-Roles", "ADMIN", "X-Customer-Id", "123").statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/tickets/bookings/" + c.bookingId(), other, null).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/tickets/" + id + "/validity", other, "{\"controlCode\":\"" + "A".repeat(43) + "\"}").statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/tickets/me?customerId=123", other, null).body()).doesNotContain(id.toString());
    }
    @Test void adminCanReadButOrganizerCannotAndNoHttpEndpointIssuesTickets() throws Exception {
        var id = issue.issue(TicketIssuanceTest.confirmation(TicketIssuanceTest.IDS.incrementAndGet(), 123, 1)).getFirst();
        assertThat(request("GET", "/api/tickets/" + id, tokenFor("admin", "ADMIN"), null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/tickets/" + id, tokenFor("organizer", "ORGANIZER"), null).statusCode()).isEqualTo(403);
        for (String role : List.of("CUSTOMER", "ADMIN", "ORGANIZER")) {
            assertThat(request("POST", "/api/tickets", tokenFor("owner", role), "{\"bookingId\":123}").statusCode()).isEqualTo(403);
            assertThat(request("POST", "/internal/tickets/issue", tokenFor("owner", role), "{}").statusCode()).isEqualTo(403);
        }
    }
    @Test void resolutionFailuresAreFailClosedAndMissingTicketsReturn404() throws Exception {
        assertThat(request("GET", "/api/tickets/me", tokenFor("missing", "CUSTOMER"), null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/tickets/me", tokenFor("down", "CUSTOMER"), null).statusCode()).isEqualTo(503);
        assertThat(request("GET", "/api/tickets/me", tokenFor("redirect", "CUSTOMER"), null).statusCode()).isEqualTo(503);
        assertThat(request("GET", "/api/tickets/" + UUID.randomUUID(), tokenFor("owner", "CUSTOMER"), null).statusCode()).isEqualTo(404);
        assertThatThrownBy(() -> query.mine()).isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
    }
    @Test void healthIsMinimalAndDiagnosticsAreNotExposed() throws Exception {
        var health = request("GET", "/actuator/health", null, null);
        assertThat(health.statusCode()).isEqualTo(200); assertThat(health.body()).doesNotContain("components", "details");
        assertThat(request("GET", "/actuator/env", tokenFor("admin", "ADMIN"), null).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/h2-console", tokenFor("admin", "ADMIN"), null).statusCode()).isEqualTo(403);
    }
    @Test void expiredTicketIsNotValidAndMalformedCodesAreRejected() throws Exception {
        Instant at = Instant.now().minusSeconds(7200);
        var confirmation = new com.ticketflow.ticketservice.domain.BookingConfirmation(UUID.randomUUID(), 1,
                "booking-service", TicketIssuanceTest.IDS.incrementAndGet(), 123, 1, "CONFIRMED", "SUCCEEDED", "test-payment", at,
                List.of(new com.ticketflow.ticketservice.domain.BookingConfirmation.Line("expired-line", 42, 1, at.plusSeconds(3600))));
        var id = issue.issue(confirmation).getFirst(); String owner = tokenFor("owner", "CUSTOMER");
        var detail = request("GET", "/api/tickets/" + id, owner, null);
        assertThat(detail.body()).contains("EXPIRED");
        String code = com.jayway.jsonpath.JsonPath.read(detail.body(), "$.controlCode");
        var validity = request("POST", "/api/tickets/" + id + "/validity", owner, "{\"controlCode\":\"" + code + "\"}");
        assertThat(validity.statusCode()).isEqualTo(200);
        assertThat(validity.body()).contains("\"valid\":false", "EXPIRED");
        assertThat(request("POST", "/api/tickets/" + id + "/validity", owner, "{\"controlCode\":\"bad\"}").statusCode()).isEqualTo(400);
    }
    @Test void identityHeadersWithoutJwtNeverGrantAccess() throws Exception {
        assertThat(request("GET", "/api/tickets/me", null, null, "X-Roles", "ADMIN", "X-User-Id", "owner", "X-Customer-Id", "123").statusCode())
                .isEqualTo(401);
    }
}
