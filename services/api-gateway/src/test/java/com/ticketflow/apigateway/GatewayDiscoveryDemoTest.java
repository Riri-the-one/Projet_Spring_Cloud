package com.ticketflow.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("discovery-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayDiscoveryDemoTest extends GatewayJwtSupport {
    private static final HttpServer backend = startBackend();

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = exchange.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry registry) {
        for (String service : java.util.List.of("customer-service", "config-server", "discovery-server")) {
            registry.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                    () -> "http://127.0.0.1:" + backend.getAddress().getPort());
        }
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void optionalLocatorRewritesOnlyTheCustomerBusinessPath() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/customer-service/api/customers/1")).header("Authorization", "Bearer " + token("ADMIN"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("/api/customers/1");
            assertThat(response.headers().firstValue("X-Served-By")).contains("api-gateway");
        }
    }

    @Test
    void discoveryAliasCannotBypassAuthenticationOrRoles() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/customer-service/api/customers/1")).GET();
            assertThat(client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            builder.header("Authorization", "Bearer " + token("ORGANIZER"));
            assertThat(client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }

    @Test
    void optionalLocatorDoesNotExposeInfrastructureOrCustomerAdministration() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : java.util.List.of("/config-server/customer-service/default",
                    "/discovery-server/eureka/apps", "/customer-service/actuator/health",
                    "/customer-service/api/config/message")) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer " + token("ADMIN"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(403);
            }
        }
    }
}
