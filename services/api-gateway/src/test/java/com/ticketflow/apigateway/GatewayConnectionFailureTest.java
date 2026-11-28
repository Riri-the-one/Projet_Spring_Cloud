package com.ticketflow.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="eureka.client.enabled=false")
class GatewayConnectionFailureTest extends GatewayJwtSupport {
    static final Socket RESERVED=new Socket();
    static { try { RESERVED.bind(new InetSocketAddress("127.0.0.1",0)); } catch(Exception ex) { throw new ExceptionInInitializerError(ex); } }
    @DynamicPropertySource static void staleAddress(DynamicPropertyRegistry p) {
        for(String service:java.util.List.of("customer-service","event-service","booking-service"))
            p.add("spring.cloud.discovery.client.simple.instances."+service+"[0].uri",()->"http://127.0.0.1:"+RESERVED.getLocalPort());
    }
    @AfterAll static void close()throws Exception { RESERVED.close(); }
    @Value("${local.server.port}") int port;
    @ParameterizedTest @ValueSource(strings={"/api/customers","/api/events","/api/bookings"})
    void refusedConnectionIs503WithoutLeakingAddressOrRetrying(String path)throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Authorization","Bearer "+token("ADMIN")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).isEqualTo("{\"code\":\"UPSTREAM_UNAVAILABLE\"}");
        }
    }
}
