package com.ticketflow.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayUnavailableTest extends GatewayJwtSupport {
    @Value("${local.server.port}")
    int port;

    @Test
    void returns503WhenNoServiceInstanceIsRegistered() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : java.util.List.of("/api/customers", "/api/events", "/api/bookings")) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer " + token("ADMIN"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(path).isEqualTo(503);
            }
        }
    }
}
