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
}