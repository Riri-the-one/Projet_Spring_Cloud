package com.ticketflow.notificationservice;
import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.ticketflow.notificationservice.domain.*;
import com.ticketflow.notificationservice.service.*;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"ticketflow.notification.max-attempts=1","ticketflow.notification.simulated-fail-first=1"})
@ActiveProfiles("demo-h2")
class NotificationApiTest extends NotificationJwtSupport {
    static final HttpServer CUSTOMER;
    static {
        try{
            CUSTOMER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            CUSTOMER.createContext("/internal/customers/me",e->{
                int status=404;String body="{}";
                try{String subject=SignedJWT.parse(e.getRequestHeaders().getFirst("Authorization").substring(7)).getJWTClaimsSet().getSubject();
                    if(subject.equals("owner")){status=200;body="{\"customerId\":123}";}else if(subject.equals("other")){status=200;body="{\"customerId\":456}";}
                }catch(Exception ignored){}
                byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}
            });CUSTOMER.start();Runtime.getRuntime().addShutdownHook(new Thread(()->CUSTOMER.stop(0)));
        }catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    @DynamicPropertySource static void customer(DynamicPropertyRegistry p){p.add("ticketflow.customer-url",()->"http://127.0.0.1:"+CUSTOMER.getAddress().getPort());}
    @Value("${local.server.port}") int port;
    @Autowired NotificationInbox inbox;@Autowired DeliveryWorker worker;
    HttpResponse<String> call(String method,String path,String bearer)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-Customer-Id","123").header("X-Roles","ADMIN")
            .method(method,HttpRequest.BodyPublishers.noBody());if(bearer!=null)b.header("Authorization","Bearer "+bearer);
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @ParameterizedTest @ValueSource(strings={"absent","expired","signature","issuer","audience","subject","algorithm","nbf"})
    void invalidJwtCannotReadHistory(String scenario)throws Exception{
        String jwt=switch(scenario){case "absent"->null;case "expired"->token(KEY,JWSAlgorithm.RS256,c->c.expirationTime(Date.from(Instant.now().minusSeconds(120))),"ADMIN");
            case "signature"->token(OTHER_KEY,JWSAlgorithm.RS256,c->{},"ADMIN");case "issuer"->token(KEY,JWSAlgorithm.RS256,c->c.issuer("https://invalid.test"),"ADMIN");
            case "audience"->token(KEY,JWSAlgorithm.RS256,c->c.audience("wrong"),"ADMIN");case "subject"->token(KEY,JWSAlgorithm.RS256,c->c.subject(null),"ADMIN");
            case "nbf"->token(KEY,JWSAlgorithm.RS256,c->c.notBeforeTime(Date.from(Instant.now().plusSeconds(120))),"ADMIN");default->token(KEY,JWSAlgorithm.RS512,c->{},"ADMIN");};
        assertThat(call("GET","/api/notifications/me",jwt).statusCode()).isEqualTo(401);
    }
    @Test void ownershipAndRolesApplyWithoutTrustingClientHeaders()throws Exception{
        var c=new NotificationCommand(UUID.randomUUID(),123,123,NotificationCommand.Kind.CONFIRMATION,Instant.now());inbox.accept(c);
        assertThat(call("GET","/api/notifications/"+c.eventId(),tokenFor("owner","CUSTOMER")).statusCode()).isEqualTo(200);
        assertThat(call("GET","/api/notifications/me",tokenFor("owner","CUSTOMER")).body()).contains(c.eventId().toString());
        assertThat(call("GET","/api/notifications/"+c.eventId(),tokenFor("other","CUSTOMER")).statusCode()).isEqualTo(403);
        assertThat(call("GET","/api/notifications/me?customerId=123",tokenFor("other","CUSTOMER")).body()).doesNotContain(c.eventId().toString());
        assertThat(call("GET","/api/notifications/me",tokenFor("owner","ORGANIZER")).statusCode()).isEqualTo(403);
        assertThat(call("GET","/api/notifications",tokenFor("owner","CUSTOMER")).statusCode()).isEqualTo(403);
        assertThat(call("GET","/api/notifications",tokenFor("admin","ADMIN")).statusCode()).isEqualTo(200);
        assertThat(call("GET","/api/notifications/me",tokenFor("missing","CUSTOMER")).statusCode()).isEqualTo(404);
        assertThat(call("GET","/api/notifications/"+UUID.randomUUID(),tokenFor("owner","CUSTOMER")).statusCode()).isEqualTo(404);
    }
    @Test void onlyAdminCanRetryExhaustedDeliveryAndReplayStillDeduplicates()throws Exception{
        var c=NotificationCoreTest.fixture();inbox.accept(c);worker.deliver(c.eventId());String path="/api/notifications/"+c.eventId()+"/retry";
        assertThat(call("POST",path,tokenFor("other","CUSTOMER")).statusCode()).isEqualTo(403);
        assertThat(call("POST",path,tokenFor("admin","ADMIN")).statusCode()).isEqualTo(200);
        worker.deliver(c.eventId());inbox.accept(c);
        assertThat(call("POST",path,tokenFor("admin","ADMIN")).statusCode()).isEqualTo(409);
    }
    @Test void noPublicIngressOrDiagnosticAndHealthIsMinimal()throws Exception{
        assertThat(call("POST","/api/notifications",tokenFor("admin","ADMIN")).statusCode()).isEqualTo(403);
        assertThat(call("POST","/internal/notifications/accept",tokenFor("admin","ADMIN")).statusCode()).isEqualTo(403);
        assertThat(call("GET","/actuator/env",tokenFor("admin","ADMIN")).statusCode()).isEqualTo(403);
        assertThat(call("GET","/api/notifications/quarantine",tokenFor("owner","CUSTOMER")).statusCode()).isEqualTo(403);
        var health=call("GET","/actuator/health",null);assertThat(health.statusCode()).isEqualTo(200);assertThat(health.body()).doesNotContain("components","details");
        assertThat(call("GET","/api/notifications/me?page=-1",tokenFor("owner","CUSTOMER")).statusCode()).isEqualTo(400);
    }
}
