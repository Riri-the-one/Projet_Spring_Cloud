package com.ticketflow.ticketservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real signatures and a local JWKS endpoint; no Keycloak, network dependency or mocked decoder. */
public abstract class TicketJwtSupport {
    protected static final String ISSUER = "https://issuer.ticketflow.test/realms/ticketflow";
    protected static final RSAKey KEY;
    protected static final RSAKey OTHER_KEY;
    protected static final HttpServer JWKS;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            OTHER_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/certs", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            JWKS.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> JWKS.stop(0)));
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    protected static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/certs");
        registry.add("ticketflow.security.audience", () -> "ticketflow-api");
        registry.add("ticketflow.security.cors-origins", () -> "http://localhost:4200");
    }

    protected static String token(String... roles) {
        return token(KEY, JWSAlgorithm.RS256, claims -> {}, roles);
    }

    protected static String tokenFor(String subject, String... roles) {
        return token(KEY, JWSAlgorithm.RS256, claims -> claims.subject(subject), roles);
    }

    protected static String token(RSAKey signingKey, JWSAlgorithm algorithm,
                        Consumer<JWTClaimsSet.Builder> changes, String... roles) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("demo-subject")
                .audience("ticketflow-api").issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("realm_access", Map.of("roles", List.of(roles)));
        changes.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID("test-key").build(), claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
