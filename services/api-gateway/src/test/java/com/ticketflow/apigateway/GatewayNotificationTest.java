package com.ticketflow.apigateway;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties="eureka.client.enabled=false")
class GatewayNotificationTest extends GatewayJwtSupport {
    static final HttpServer BACKEND;
    static final AtomicReference<String> AUTH = new AtomicReference<>();
    static final AtomicReference<String> SPOOF = new AtomicReference<>();
    static {
        try {
            BACKEND=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            BACKEND.createContext("/", exchange -> {
                AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                SPOOF.set(exchange.getRequestHeaders().getFirst("X-Customer-Id"));
                byte[] bytes=(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            });
            BACKEND.start();
        } catch(Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void discovery(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.discovery.client.simple.instances.notification-service[0].uri", () -> "http://127.0.0.1:"+BACKEND.getAddress().getPort());
    }
    @AfterAll static void stop(){ BACKEND.stop(0); }
    @Value("${local.server.port}") int port;
    HttpResponse<String> call(String method,String path,String jwt) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("X-Customer-Id","999").header("X-Roles","ADMIN")
                .method(method,method.equals("POST")?HttpRequest.BodyPublishers.ofString("{}"):HttpRequest.BodyPublishers.noBody());
        if(jwt!=null) builder.header("Authorization","Bearer "+jwt);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @ParameterizedTest
    @CsvSource({"GET,/api/notifications/me", "GET,/api/notifications/5fd4c48f-dce8-4930-a998-a2ba2fdf63c0"})
    void onlyPersonalReadsAreForwardedWithAValidatedJwt(String method,String path) throws Exception {
        for(String role:java.util.List.of("CUSTOMER","ADMIN")) {
            String jwt=token(role); var response=call(method,path,jwt);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(method+" "+path);
            assertThat(AUTH.get()).isEqualTo("Bearer "+jwt); assertThat(SPOOF.get()).isNull();
        }
        assertThat(call(method,path,null).statusCode()).isEqualTo(401);
        assertThat(call(method,path,token("ORGANIZER")).statusCode()).isEqualTo(403);
    }
    @ParameterizedTest
    @CsvSource({"POST,/api/notifications", "POST,/api/notifications/123/retry", "GET,/api/notifications/quarantine",
        "GET,/api/notifications", "PUT,/api/notifications/123", "DELETE,/api/notifications/123",
        "POST,/internal/notifications/accept", "GET,/notification-service/actuator/health"})
    void injectionAndAdministrativeOperationsAreNotExposed(String method,String path) throws Exception {
        assertThat(call(method,path,token("ADMIN")).statusCode()).isEqualTo(403);
    }
    @Test void legitimateCorsPreflightIsAllowed() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/notifications/me"))
            .header("Origin","http://localhost:4200").header("Access-Control-Request-Method","GET")
            .header("Access-Control-Request-Headers","authorization").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:4200");
    }
}