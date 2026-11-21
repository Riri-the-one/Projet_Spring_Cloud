package com.ticketflow.eventservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import java.util.Collection;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
@Configuration
public class EventSecurityConfig {

    @Bean
    SecurityFilterChain stockSecurity(
            HttpSecurity http,
            @Value("${ticketflow.security.booking-subject}") String bookingSubject
    ) throws Exception {
        return http
                .securityMatcher("/internal/events/*/seat-holds/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes.anyRequest().access(
                        (authentication, context) -> new AuthorizationDecision(
                                authentication.get() instanceof JwtAuthenticationToken token
                                        && !bookingSubject.isBlank()
                                        && bookingSubject.equals(token.getToken().getSubject())
                        )
                ))
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }
    @Bean
    SecurityFilterChain eventApiSecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/events", "/api/events/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/", "/api/events/*")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/events", "/api/events/")
                        .hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/events/*")
                        .hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/events/*/publish", "/api/events/*/close")
                        .hasAnyRole("ORGANIZER", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(token -> {
                            var realmAccess = token.getClaimAsMap("realm_access");
                            var roles = realmAccess == null ? null : realmAccess.get("roles");

                            var authorities = roles instanceof Collection<?> values
                                    ? values.stream()
                                    .filter(role -> "ORGANIZER".equals(role) || "ADMIN".equals(role))
                                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                    .toList()
                                    : java.util.List.<SimpleGrantedAuthority>of();

                            return new JwtAuthenticationToken(token, authorities);
                        })))
                .build();
    }
}