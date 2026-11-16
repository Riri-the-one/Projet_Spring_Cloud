package com.ticketflow.apigateway.security;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;

/** Phase 1: boundary authorization only. Resource ownership belongs to each business service. */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {
    private static final Set<String> ROLES = Set.of("CUSTOMER", "ORGANIZER", "ADMIN");

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
            @Value("${ticketflow.security.audience}") String audience) {
        Assert.hasText(audience, "A non-empty JWT audience is required");
        // Only RS256 is accepted; key material comes from the configured server, never the token.
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            boolean valid = jwt.getExpiresAt() != null
                    && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                    && jwt.getAudience() != null && jwt.getAudience().contains(audience);
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                            "Required subject, expiration or audience is missing or invalid", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)), new JwtIssuerValidator(issuer), requiredClaims));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, Environment environment) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // Bearer API: no authentication cookies.
                .cors(cors -> {})
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(exchanges -> {
                    exchanges.pathMatchers(HttpMethod.GET, "/actuator/health").permitAll();
                    exchanges.pathMatchers(HttpMethod.GET, "/api/events", "/api/events/").permitAll();
                    // Detail can contain drafts today: restrict until Manar implements visibility.
                    exchanges.pathMatchers(HttpMethod.GET, "/api/events/*")
                            .hasAnyRole("CUSTOMER", "ORGANIZER", "ADMIN");
                    exchanges.pathMatchers(HttpMethod.POST, "/api/events", "/api/events/",
                            "/api/events/*/publish", "/api/events/*/close").hasAnyRole("ORGANIZER", "ADMIN");
                    exchanges.pathMatchers(HttpMethod.PUT, "/api/events/*").hasAnyRole("ORGANIZER", "ADMIN");
                    String[] customerBases = environment.matchesProfiles("discovery-demo")
                            ? new String[]{"/api/customers", "/customer-service/api/customers"}
                            : new String[]{"/api/customers"};
                    for (String base : customerBases) {
                        exchanges.pathMatchers(HttpMethod.GET, base + "/whoami").hasRole("ADMIN");
                        exchanges.pathMatchers(HttpMethod.GET, base, base + "/").hasRole("ADMIN");
                        exchanges.pathMatchers(HttpMethod.POST, base, base + "/").hasAnyRole("CUSTOMER", "ADMIN");
                        exchanges.pathMatchers(HttpMethod.GET, base + "/*").hasAnyRole("CUSTOMER", "ADMIN");
                        exchanges.pathMatchers(HttpMethod.PUT, base + "/*").hasAnyRole("CUSTOMER", "ADMIN");
                        exchanges.pathMatchers(HttpMethod.DELETE, base + "/*").hasRole("ADMIN");
                    }
                    exchanges.pathMatchers(HttpMethod.GET, "/api/bookings", "/api/bookings/", "/api/bookings/*")
                            .hasAnyRole("CUSTOMER", "ADMIN");
                    exchanges.pathMatchers(HttpMethod.POST, "/api/bookings", "/api/bookings/",
                            "/api/bookings/*/cancel").hasAnyRole("CUSTOMER", "ADMIN");
                    exchanges.pathMatchers(HttpMethod.GET, "/api/tickets/me", "/api/tickets/*", "/api/tickets/bookings/*")
                            .hasAnyRole("CUSTOMER", "ADMIN");
                    exchanges.pathMatchers(HttpMethod.POST, "/api/tickets/*/validity").hasAnyRole("CUSTOMER", "ADMIN");
                    exchanges.pathMatchers(HttpMethod.GET, "/api/notifications/me", "/api/notifications/{id:[0-9a-fA-F-]+}")
                            .hasAnyRole("CUSTOMER", "ADMIN");
                    exchanges.anyExchange().denyAll();
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                    Object realm = token.getClaims().get("realm_access");
                    Object roles = realm instanceof Map<?, ?> map ? map.get("roles") : null;
                    var authorities = roles instanceof Collection<?> values
                            ? values.stream().filter(String.class::isInstance).map(String.class::cast)
                                    .filter(ROLES::contains).distinct()
                                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()
                            : List.<SimpleGrantedAuthority>of();
                    return Mono.just(new JwtAuthenticationToken(token, authorities));
                })))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${ticketflow.security.cors-origins}") List<String> origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setExposedHeaders(List.of("Location", "X-Served-By"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
