package com.ticketflow.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityTest extends GatewayJwtSupport {
    static final AtomicInteger backendCalls = new AtomicInteger();
    static final AtomicReference<String> backendAuthorization = new AtomicReference<>();
    static final AtomicReference<String> backendSpoofedRoles = new AtomicReference<>();
    static final AtomicReference<String> backendSpoofedUser = new AtomicReference<>();
    static final AtomicReference<String> backendSpoofedCustomer = new AtomicReference<>();
    static final HttpServer backend = startBackend();

    static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                backendCalls.incrementAndGet();
                backendAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                backendSpoofedRoles.set(exchange.getRequestHeaders().getFirst("X-Roles"));
                backendSpoofedUser.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                backendSpoofedCustomer.set(exchange.getRequestHeaders().getFirst("X-Customer-Id"));
                byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
            return server;
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        for (String name : List.of("customer-service", "event-service", "booking-service", "config-server")) {
            registry.add("spring.cloud.discovery.client.simple.instances." + name + "[0].uri",
                    () -> "http://127.0.0.1:" + backend.getAddress().getPort());
        }
    }

    @AfterAll
    static void stopBackend() { backend.stop(0); }

    @Value("${local.server.port}") int port;

    HttpResponse<String> request(String method, String path, String bearer, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (bearer != null) { builder.header("Authorization", "Bearer " + bearer); }
        for (int index = 0; index < headers.length; index += 2) { builder.header(headers[index], headers[index + 1]); }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void privateRouteRequiresJwtAndIgnoresForgedIdentityHeaders() throws Exception {
        int before = backendCalls.get();
        var response = request("GET", "/api/bookings", null,
                "X-Roles", "ADMIN", "X-User-Id", "admin", "X-Customer-Id", "1", "Accept", "text/html");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(backendCalls.get()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "signature", "issuer", "audience", "future", "subject", "blank-subject",
            "no-expiration", "no-audience", "no-issuer", "algorithm", "malformed"})
    void rejectsInvalidSignedTokens(String scenario) throws Exception {
        String jwt = switch (scenario) {
            case "expired" -> token(KEY, JWSAlgorithm.RS256,
                    c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120))), "ADMIN");
            case "signature" -> token(OTHER_KEY, JWSAlgorithm.RS256, c -> {}, "ADMIN");
            case "issuer" -> token(KEY, JWSAlgorithm.RS256, c -> c.issuer("https://attacker.test"), "ADMIN");
            case "audience" -> token(KEY, JWSAlgorithm.RS256, c -> c.audience("another-api"), "ADMIN");
            case "future" -> token(KEY, JWSAlgorithm.RS256,
                    c -> c.notBeforeTime(Date.from(Instant.now().plusSeconds(120))), "ADMIN");
            case "subject" -> token(KEY, JWSAlgorithm.RS256, c -> c.subject(null), "ADMIN");
            case "blank-subject" -> token(KEY, JWSAlgorithm.RS256, c -> c.subject(" "), "ADMIN");
            case "no-expiration" -> token(KEY, JWSAlgorithm.RS256, c -> c.expirationTime(null), "ADMIN");
            case "no-audience" -> token(KEY, JWSAlgorithm.RS256, c -> c.audience(List.of()), "ADMIN");
            case "no-issuer" -> token(KEY, JWSAlgorithm.RS256, c -> c.issuer(null), "ADMIN");
            case "algorithm" -> token(KEY, JWSAlgorithm.RS512, c -> {}, "ADMIN");
            default -> "not.a.jwt";
        };
        int before = backendCalls.get();
        assertThat(request("GET", "/api/bookings", jwt).statusCode()).isEqualTo(401);
        assertThat(backendCalls.get()).isEqualTo(before);
    }

    @Test
    void catalogIsPublicButEventDetailIsNotYetPublic() throws Exception {
        assertThat(request("GET", "/api/events?venue=Paris", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/events/1", null).statusCode()).isEqualTo(200);
    }

    @Test
    void validTokenWithoutApplicationRoleIsForbidden() throws Exception {
        assertThat(request("GET", "/api/bookings", token()).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/bookings", token("ROLE_ADMIN", "admin")).statusCode()).isEqualTo(403);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/bookings,CUSTOMER,200", "POST,/api/bookings,CUSTOMER,200",
            "POST,/api/bookings/1/cancel,CUSTOMER,200", "GET,/api/bookings,ORGANIZER,403",
            "POST,/api/events,CUSTOMER,403", "POST,/api/events,ORGANIZER,200",
            "PUT,/api/events/1,ORGANIZER,200", "POST,/api/events/1/publish,ORGANIZER,200",
            "POST,/api/events/1/close,ORGANIZER,200", "DELETE,/api/events/1,ADMIN,403",
            "GET,/api/customers,CUSTOMER,403", "GET,/api/customers,ADMIN,200",
            "POST,/api/customers,CUSTOMER,200", "GET,/api/customers/1,CUSTOMER,200",
            "PUT,/api/customers/1,CUSTOMER,200", "DELETE,/api/customers/1,CUSTOMER,403",
            "DELETE,/api/customers/1,ADMIN,200", "GET,/api/customers/whoami,CUSTOMER,403",
            "GET,/api/customers/whoami,ADMIN,200", "PATCH,/api/customers/1,ADMIN,403",
            "PUT,/api/bookings/1,ADMIN,403", "POST,/api/events/1/unknown,ADMIN,403"
    })
    void enforcesMethodAndIndependentRoles(String method, String path, String role, int status) throws Exception {
        assertThat(request(method, path, token(role)).statusCode()).isEqualTo(status);
    }

    @Test
    void forwardedTokenIsPreservedAndSpoofedIdentityIsRemoved() throws Exception {
        String bearer = token("CUSTOMER");
        assertThat(request("GET", "/api/bookings", bearer, "X-Roles", "ADMIN",
                "X-User-Id", "other-user", "X-Customer-Id", "99").statusCode()).isEqualTo(200);
        assertThat(backendAuthorization.get()).isEqualTo("Bearer " + bearer);
        assertThat(backendSpoofedRoles.get()).isNull();
        assertThat(backendSpoofedUser.get()).isNull();
        assertThat(backendSpoofedCustomer.get()).isNull();
        assertThat(request("POST", "/api/events", bearer, "X-Roles", "ADMIN").statusCode()).isEqualTo(403);
    }

    @Test
    void internalAndUnknownRoutesAreDeniedEvenToAdmin() throws Exception {
        for (String path : List.of("/eureka/apps", "/config-server/customer-service/default", "/admin/realms",
                "/api/config/message", "/internal/customers/me", "/actuator/env", "/actuator/gateway/routes",
                "/customer-service/api/customers", "/api/unknown")) {
            assertThat(request("GET", path, token("ADMIN")).statusCode()).as(path).isEqualTo(403);
        }
    }

    @Test
    void legitimateCorsPreflightDoesNotNeedAuthentication() throws Exception {
        var response = request("OPTIONS", "/api/bookings", null, "Origin", "http://localhost:4200",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization,content-type");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:4200");
        assertThat(request("OPTIONS", "/api/bookings", null, "Origin", "https://attacker.test",
                "Access-Control-Request-Method", "POST").statusCode()).isEqualTo(403);
    }

    @Test
    void healthIsPublicAndDoesNotDiscloseComponents() throws Exception {
        var response = request("GET", "/actuator/health", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("components", "details");
    }
}
