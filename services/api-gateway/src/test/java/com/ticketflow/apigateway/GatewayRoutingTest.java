package com.ticketflow.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Starts the gateway on a random port and a tiny stub HTTP server standing in for
 * customer-service, so the routing can be tested without running the real service.
 *
 * <p>The real explicit route configuration is imported by the test configuration.
 * Eureka is replaced by SimpleDiscoveryClient; the real load balancer still resolves lb://.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
class GatewayRoutingTest extends GatewayJwtSupport {

    private static final AtomicReference<String> lastBackendPath = new AtomicReference<>();
    private static final AtomicReference<String> lastBackendBody = new AtomicReference<>();
    private static final HttpServer backend = startBackend();

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                lastBackendPath.set(exchange.getRequestURI().getPath());
                lastBackendBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = "{\"id\":1,\"firstName\":\"Sara\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                String path = exchange.getRequestURI().getPath();
                int status = path.endsWith("/missing") ? 404
                        : path.endsWith("/invalid") ? 400
                        : path.endsWith("/duplicate") ? 409
                        : path.endsWith("/unavailable") ? 503
                        : exchange.getRequestMethod().equals("POST") ? 201 : 200;
                if (status == 201) {
                    exchange.getResponseHeaders().add("Location",
                            "http://" + exchange.getRequestHeaders().getFirst("Host") + path + "/1");
                }
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void backendUrl(DynamicPropertyRegistry registry) {
        // Declares the stub as the one and only instance of "customer-service"
        registry.add("spring.cloud.discovery.client.simple.instances.customer-service[0].uri",
                () -> "http://127.0.0.1:" + backend.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.event-service[0].uri",
                () -> "http://127.0.0.1:" + backend.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.booking-service[0].uri",
                () -> "http://127.0.0.1:" + backend.getAddress().getPort());
        // Even a registered internal service must not acquire a public route.
        registry.add("spring.cloud.discovery.client.simple.instances.config-server[0].uri",
                () -> "http://127.0.0.1:" + backend.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token("ADMIN"))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void forwardsCustomerRequestsToTheBackend() throws Exception {
        HttpResponse<String> response = get("/api/customers/1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Sara");
        assertThat(lastBackendPath.get()).isEqualTo("/api/customers/1");
    }

    @Test
    void addsTheGatewayResponseHeader() throws Exception {
        HttpResponse<String> response = get("/api/customers");

        assertThat(response.headers().firstValue("X-Served-By")).contains("api-gateway");
    }

    @Test
    void serviceNameRoutesAreNotExposedByDefault() throws Exception {
        assertThat(get("/customer-service/api/customers").statusCode()).isEqualTo(403);
    }

    @Test
    void unknownPathsAreNotRouted() throws Exception {
        assertThat(get("/api/unknown").statusCode()).isEqualTo(403);
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    void preservesCreationStatusLocationAndRequestBody() throws Exception {
        String body = "{\"firstName\":\"Sara\",\"lastName\":\"Idrissi\",\"email\":\"sara@example.com\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/customers"))
                .header("Authorization", "Bearer " + token("ADMIN"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(lastBackendBody.get()).isEqualTo(body);
        assertThat(response.headers().firstValue("Location"))
                .contains("http://127.0.0.1:" + port + "/api/customers/1");
        assertThat(response.headers().firstValue("X-Served-By")).contains("api-gateway");
        assertThat(get(URI.create(response.headers().firstValue("Location").orElseThrow()).getPath())
                .statusCode()).isEqualTo(200);
    }

    @Test
    void forwardsEventRequestsWithoutRewriting() throws Exception {
        assertThat(get("/api/events/1").statusCode()).isEqualTo(200);
        assertThat(lastBackendPath.get()).isEqualTo("/api/events/1");
    }

    @Test
    void forwardsBookingRequestsWithoutRewriting() throws Exception {
        assertThat(get("/api/bookings/1").statusCode()).isEqualTo(200);
        assertThat(lastBackendPath.get()).isEqualTo("/api/bookings/1");
    }

    @Test
    void propagatesBackendErrors() throws Exception {
        for (var entry : java.util.Map.of("missing", 404, "invalid", 400, "duplicate", 409, "unavailable", 503).entrySet()) {
            HttpResponse<String> response = get("/api/customers/" + entry.getKey());
            assertThat(response.statusCode()).isEqualTo(entry.getValue());
            assertThat(response.body()).contains("Sara");
            assertThat(response.headers().firstValue("X-Served-By")).contains("api-gateway");
        }
    }

    @Test
    void doesNotExposeInternalServicesOrAdministration() throws Exception {
        for (String path : java.util.List.of("/config-server/customer-service/default", "/discovery-server/eureka/apps",
                "/eureka/apps", "/api/config/message", "/actuator/gateway/routes", "/actuator/env",
                "/customer-service/actuator/health", "/event-service/actuator/health")) {
            assertThat(get(path).statusCode()).as(path).isEqualTo(403);
        }
    }
}
