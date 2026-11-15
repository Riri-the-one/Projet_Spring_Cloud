package com.ticketflow.customerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.ticketflow.customerservice.entities.Customer;
import com.ticketflow.customerservice.repos.CustomerRepo;
import com.ticketflow.customerservice.service.CustomerService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** HTTP tests with the actual servlet filter chain, RSA verification, JPA and H2 transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerIdentityApiTest extends CustomerJwtSupport {
    @Value("${local.server.port}") int port;
    @Autowired CustomerRepo repo;
    @Autowired CustomerService service;
    @Autowired JwtDecoder decoder;

    String subject() { return UUID.randomUUID().toString(); }
    String profile(String email) {
        return "{\"firstName\":\"Client\",\"lastName\":\"Fictif\",\"email\":\"" + email + "\",\"phone\":\"123\"}";
    }
    String email() { return subject() + "@example.invalid"; }
    HttpResponse<String> request(String method, String path, String bearer, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(15))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) builder.header("Content-Type", "application/json");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    long id(HttpResponse<String> response) { return ((Number) JsonPath.read(response.body(), "$.id")).longValue(); }
    Customer fixture(String subject) {
        return repo.saveAndFlush(new Customer("Original", "Fictif", email(), "123", ISSUER, subject));
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "expired", "signature", "issuer", "audience", "subject", "blank-subject",
            "future", "algorithm", "no-expiration", "no-audience", "no-issuer", "long-subject", "malformed"})
    void rejectsInvalidJwtBeforeCallingBusinessCode(String scenario) throws Exception {
        String jwt = switch (scenario) {
            case "absent" -> null;
            case "expired" -> token(KEY, JWSAlgorithm.RS256, c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120))), "ADMIN");
            case "signature" -> token(OTHER_KEY, JWSAlgorithm.RS256, c -> {}, "ADMIN");
            case "issuer" -> token(KEY, JWSAlgorithm.RS256, c -> c.issuer("https://attacker.test"), "ADMIN");
            case "audience" -> token(KEY, JWSAlgorithm.RS256, c -> c.audience("other-api"), "ADMIN");
            case "subject" -> token(KEY, JWSAlgorithm.RS256, c -> c.subject(null), "ADMIN");
            case "blank-subject" -> tokenFor(" ", "ADMIN");
            case "future" -> token(KEY, JWSAlgorithm.RS256, c -> c.notBeforeTime(Date.from(Instant.now().plusSeconds(120))), "ADMIN");
            case "algorithm" -> token(KEY, JWSAlgorithm.RS512, c -> {}, "ADMIN");
            case "no-expiration" -> token(KEY, JWSAlgorithm.RS256, c -> c.expirationTime(null), "ADMIN");
            case "no-audience" -> token(KEY, JWSAlgorithm.RS256, c -> c.audience(List.of()), "ADMIN");
            case "no-issuer" -> token(KEY, JWSAlgorithm.RS256, c -> c.issuer(null), "ADMIN");
            case "long-subject" -> tokenFor("x".repeat(256), "ADMIN");
            default -> "not.a.jwt";
        };
        long before = repo.count();
        assertThat(request("POST", "/api/customers", jwt, profile(email())).statusCode()).isEqualTo(401);
        assertThat(repo.count()).isEqualTo(before);
    }

    @Test
    void createsAndUpdatesOwnProfileWithoutChangingItsIdentity() throws Exception {
        String sub = subject();
        String jwt = tokenFor(sub, "CUSTOMER");
        String contact = email();
        var created = request("POST", "/api/customers", jwt, profile(contact));
        assertThat(created.statusCode()).isEqualTo(201);
        long customerId = id(created);
        assertThat(created.headers().firstValue("Location")).isPresent();
        assertThat(created.body()).doesNotContain("identityIssuer", "identitySubject");
        assertThat(repo.findById(customerId).orElseThrow().getIdentityIssuer()).isEqualTo(ISSUER);
        assertThat(repo.findById(customerId).orElseThrow().getIdentitySubject()).isEqualTo(sub);
        assertThat(id(request("GET", "/api/customers/me", jwt, null))).isEqualTo(customerId);
        assertThat(request("GET", "/api/customers/" + customerId, jwt, null).statusCode()).isEqualTo(200);
        String forged = profile(contact).replace("\"Client\"", "\"Updated\"").replace("\"phone\":\"123\"",
                "\"phone\":\"456\",\"identityIssuer\":\"https://attacker.test\",\"identitySubject\":\"other\"");
        var updated = request("PUT", "/api/customers/me", jwt, forged);
        assertThat(updated.statusCode()).isEqualTo(400);
        assertThat(request("PUT", "/api/customers/me", jwt, profile(contact)).statusCode()).isEqualTo(200);
        assertThat(request("PUT", "/api/customers/" + customerId, jwt, profile(contact)).statusCode()).isEqualTo(200);
        Customer persisted = repo.findById(customerId).orElseThrow();
        assertThat(persisted.getIdentitySubject()).isEqualTo(sub);
        assertThat(persisted.getIdentityIssuer()).isEqualTo(ISSUER);
    }

    @Test
    void spoofedIdentityInCreationCannotChooseAnotherAccount() throws Exception {
        String sub = subject();
        String body = profile(email()).replace("\"phone\":\"123\"",
                "\"phone\":\"123\",\"identitySubject\":\"victim\",\"identityIssuer\":\"https://attacker.test\"");
        var result = request("POST", "/api/customers", tokenFor(sub, "CUSTOMER"), body,
                "X-Roles", "ADMIN", "X-User-Id", "victim", "X-Customer-Id", "1");
        assertThat(result.statusCode()).isEqualTo(400);
        assertThat(repo.findByIdentityIssuerAndIdentitySubject(ISSUER, sub)).isEmpty();
        assertThat(repo.findByIdentityIssuerAndIdentitySubject("https://attacker.test", "victim")).isEmpty();
    }

    @Test
    void anotherCustomerCannotReadUpdateOrDeleteAndNothingChanges() throws Exception {
        Customer victim = fixture(subject());
        String attacker = tokenFor(subject(), "CUSTOMER");
        String path = "/api/customers/" + victim.getId();
        assertThat(request("GET", path, attacker, null, "X-Roles", "ADMIN", "X-Customer-Id", victim.getId().toString())
                .statusCode()).isEqualTo(403);
        assertThat(request("PUT", path, attacker, profile(email())).statusCode()).isEqualTo(403);
        assertThat(request("DELETE", path, attacker, null).statusCode()).isEqualTo(403);
        Customer unchanged = repo.findById(victim.getId()).orElseThrow();
        assertThat(unchanged.getFirstName()).isEqualTo("Original");
        assertThat(unchanged.getEmail()).isEqualTo(victim.getEmail());
        assertThat(request("GET", "/api/customers/9223372036854775807", attacker, null).statusCode()).isEqualTo(404);
    }

    @Test
    void adminsCanManageOtherProfilesButCreateOnlyTheirOwn() throws Exception {
        Customer other = fixture(subject());
        String adminSub = subject();
        String admin = tokenFor(adminSub, "ADMIN");
        assertThat(request("GET", "/api/customers", admin, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/customers/" + other.getId(), admin, null).statusCode()).isEqualTo(200);
        assertThat(request("PUT", "/api/customers/" + other.getId(), admin, profile(email())).statusCode()).isEqualTo(200);
        assertThat(repo.findById(other.getId()).orElseThrow().getIdentitySubject()).isEqualTo(other.getIdentitySubject());
        var created = request("POST", "/api/customers", admin, profile(email()));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(repo.findById(id(created)).orElseThrow().getIdentitySubject()).isEqualTo(adminSub);
        assertThat(request("DELETE", "/api/customers/" + other.getId(), admin, null).statusCode()).isEqualTo(204);
        assertThat(repo.findById(other.getId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/customers/me", "/api/customers/1", "/api/customers", "/internal/customers/me"})
    void organizerAloneCannotUseCustomerApi(String path) throws Exception {
        assertThat(request("GET", path, token("ORGANIZER"), null, "X-Roles", "CUSTOMER").statusCode()).isEqualTo(403);
        assertThat(request("GET", path, null, null, "X-Roles", "ADMIN", "X-User-Id", "admin").statusCode()).isEqualTo(401);
    }

    @Test
    void noApplicationRoleAndPrefixedRoleAreNotAccepted() throws Exception {
        assertThat(request("GET", "/api/customers/me", token(), null).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/customers/me", token("ROLE_ADMIN"), null).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/customers", token("CUSTOMER"), null).statusCode()).isEqualTo(403);
    }

    @Test
    void missingProfileDoesNotProvisionAndReturnsStableCode() throws Exception {
        String jwt = tokenFor(subject(), "CUSTOMER");
        long before = repo.count();
        for (String path : List.of("/api/customers/me", "/internal/customers/me")) {
            var result = request("GET", path, jwt, null);
            assertThat(result.statusCode()).isEqualTo(404);
            assertThat(result.body()).contains("CUSTOMER_PROFILE_NOT_FOUND");
        }
        assertThat(request("PUT", "/api/customers/me", jwt, profile(email())).statusCode()).isEqualTo(404);
        assertThat(repo.count()).isEqualTo(before);
    }

    @Test
    void internalResolutionReturnsOnlyAuthenticatedCustomerId() throws Exception {
        String sub = subject();
        Customer own = fixture(sub);
        Customer other = fixture(subject());
        var result = request("GET", "/internal/customers/me?customerId=" + other.getId(), tokenFor(sub, "CUSTOMER"), null,
                "X-Customer-Id", other.getId().toString(), "X-User-Id", other.getIdentitySubject());
        assertThat(result.statusCode()).isEqualTo(200);
        Map<String, Object> data = JsonPath.read(result.body(), "$");
        assertThat(data).hasSize(1).containsKey("customerId");
        assertThat(((Number) data.get("customerId")).longValue()).isEqualTo(own.getId());
        assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void historicalProfilesCannotBeClaimedByMatchingEmail() throws Exception {
        Customer legacy = repo.saveAndFlush(new Customer("Historical", "Fictif", email(), "123"));
        String jwt = tokenFor(subject(), "CUSTOMER");
        assertThat(request("GET", "/api/customers/" + legacy.getId(), jwt, null).statusCode()).isEqualTo(403);
        assertThat(request("PUT", "/api/customers/" + legacy.getId(), jwt, profile(email())).statusCode()).isEqualTo(403);
        var duplicateEmail = request("POST", "/api/customers", jwt, profile(legacy.getEmail()));
        assertThat(duplicateEmail.statusCode()).isEqualTo(409);
        assertThat(duplicateEmail.body()).doesNotContain(legacy.getEmail());
        assertThat(request("GET", "/api/customers/me", jwt, null).statusCode()).isEqualTo(404);
        assertThat(repo.findById(legacy.getId()).orElseThrow().getIdentitySubject()).isNull();
        assertThat(request("GET", "/api/customers/" + legacy.getId(), token("ADMIN"), null).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "email"})
    void simultaneousHttpCreationsHaveOneWinnerAndRollbackCleanly(String conflict) throws Exception {
        String sub = subject();
        String sharedEmail = email();
        String first = tokenFor(sub, "CUSTOMER");
        String second = tokenFor(conflict.equals("identity") ? sub : subject(), "CUSTOMER");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { start.await(); return request("POST", "/api/customers", first, profile(sharedEmail)); });
            var two = executor.submit(() -> { start.await(); return request("POST", "/api/customers", second,
                    profile(conflict.equals("email") ? sharedEmail : email())); });
            start.countDown();
            var responses = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
            String error = responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow().body();
            assertThat(error).contains(conflict.equals("identity") ? "CUSTOMER_IDENTITY_CONFLICT" : "CUSTOMER_EMAIL_CONFLICT")
                    .doesNotContain(sharedEmail, sub, "insert into", "SQL");
        }
        if (conflict.equals("identity")) {
            assertThat(repo.findByIdentityIssuerAndIdentitySubject(ISSUER, sub)).isPresent();
        } else {
            assertThat(repo.existsByEmail(sharedEmail)).isTrue();
        }
        assertThat(request("POST", "/api/customers", tokenFor(subject(), "CUSTOMER"), profile(email())).statusCode()).isEqualTo(201);
    }

    @Test
    void updateEmailConflictDoesNotModifyTheProfile() throws Exception {
        String sub = subject();
        Customer own = fixture(sub);
        Customer other = fixture(subject());
        var conflict = request("PUT", "/api/customers/me", tokenFor(sub, "CUSTOMER"), profile(other.getEmail()));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).doesNotContain(other.getEmail());
        assertThat(repo.findById(own.getId()).orElseThrow().getEmail()).isEqualTo(own.getEmail());
    }

    @Test
    void technicalEndpointsHaveExplicitPoliciesAndHealthHasNoDetails() throws Exception {
        var health = request("GET", "/actuator/health", null, null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).doesNotContain("components", "details");
        for (String path : List.of("/api/config/message", "/api/customers/whoami")) {
            assertThat(request("GET", path, null, null).statusCode()).isEqualTo(401);
            assertThat(request("GET", path, token("CUSTOMER"), null).statusCode()).isEqualTo(403);
            assertThat(request("GET", path, token("ADMIN"), null).statusCode()).isEqualTo(200);
        }
        assertThat(request("POST", "/actuator/refresh", null, null).statusCode()).isEqualTo(401);
        assertThat(request("POST", "/actuator/refresh", token("CUSTOMER"), null).statusCode()).isEqualTo(403);
        for (String path : List.of("/h2-console", "/actuator/env", "/internal/customers/1")) {
            assertThat(request("GET", path, token("ADMIN"), null).statusCode()).isEqualTo(403);
        }
        assertThat(request("PATCH", "/api/customers/me", token("ADMIN"), null).statusCode()).isEqualTo(403);
    }

    @Test
    void serviceCannotBeInvokedWithoutAuthentication() {
        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(service::list).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void ownershipAndAdminPolicyAlsoApplyWhenCallingTheServiceDirectly() {
        Customer other = fixture(subject());
        var jwt = decoder.decode(tokenFor(subject(), "CUSTOMER"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        try {
            assertThatThrownBy(() -> service.get(other.getId())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.update(other.getId(), new com.ticketflow.customerservice.dto.CustomerRequest(
                    "Changed", "Forbidden", email(), null))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(service::list).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.delete(other.getId())).isInstanceOf(AccessDeniedException.class);
            assertThat(repo.findById(other.getId()).orElseThrow().getFirstName()).isEqualTo("Original");
        } finally { SecurityContextHolder.clearContext(); }
    }
}
