package com.ticketflow.ticketservice.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Only the JWT already validated locally is forwarded. No ID or custom identity header is accepted. */
@Component
public class CustomerIdentityResolver {
    private final RestClient customer;
    public CustomerIdentityResolver(@Value("${ticketflow.customer-url}") String baseUrl) {
        var address = java.net.URI.create(baseUrl);
        if (!java.util.Set.of("http", "https").contains(address.getScheme()) || address.getHost() == null
                || address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null) {
            throw new IllegalArgumentException("A trusted HTTP Customer service base URL is required");
        }
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false); // Never forward a bearer token through a redirect.
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(3)); factory.setReadTimeout(Duration.ofSeconds(5));
        this.customer = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
    public record Caller(Jwt jwt, boolean admin) {}
    public Caller caller() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new AuthenticationCredentialsNotFoundException("A validated JWT is required");
        }
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean client = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER"));
        if (!admin && !client) throw new AccessDeniedException("Customer access required");
        return new Caller(jwt, admin);
    }
    public long customerId(Caller caller) {
        try {
            var profile = customer.get().uri("/internal/customers/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.jwt().getTokenValue())
                    .retrieve().body(Profile.class);
            if (profile == null || profile.customerId() == null || profile.customerId() < 1) throw new CustomerResolutionException(false);
            return profile.customerId();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) throw new CustomerResolutionException(true);
            throw new CustomerResolutionException(false);
        } catch (org.springframework.web.client.RestClientException ex) { throw new CustomerResolutionException(false); }
    }
    public record Profile(Long customerId) {}
    public static class CustomerResolutionException extends RuntimeException {
        private final boolean missing;
        public CustomerResolutionException(boolean missing) { super("Customer profile resolution failed"); this.missing = missing; }
        public boolean missing() { return missing; }
    }
}
