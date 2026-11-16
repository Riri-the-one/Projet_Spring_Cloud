package com.ticketflow.ticketservice.config;

import java.time.Clock;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
public class TicketConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean static BeanFactoryPostProcessor explicitDemoStorage(Environment env) {
        return factory -> validateStorage(env);
    }
    static void validateStorage(Environment env) {
        boolean postgres = env.acceptsProfiles(Profiles.of("postgresql"));
        boolean h2 = env.acceptsProfiles(Profiles.of("demo-h2"));
        if (postgres == h2) throw new IllegalStateException("Exactly one Ticket storage profile is required");
        String url = env.getProperty("spring.datasource.url", "");
        if (h2) {
            require(url.startsWith("jdbc:h2:mem:"), "Ticket demo requires an in-memory database");
            require(!env.getProperty("spring.flyway.enabled", Boolean.class, false), "Flyway must be disabled for the H2 demo");
        } else {
            require(url.matches("jdbc:postgresql://[^/]+/ticketflow_ticket(?:\\?.*)?"), "Dedicated Ticket PostgreSQL URL required");
            require("ticketflow_ticket".equals(env.getProperty("spring.datasource.username")), "Ticket domain account required");
            require("org.postgresql.Driver".equals(env.getProperty("spring.datasource.driver-class-name")), "PostgreSQL driver required");
            require("validate".equals(env.getProperty("spring.jpa.hibernate.ddl-auto")), "Persistent schema requires ddl-auto=validate");
            require("validate".equals(env.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto", "validate")), "Unsafe native Hibernate DDL override");
            require(env.getProperty("spring.flyway.enabled", Boolean.class, false), "Ticket Flyway migrations required");
            require(!env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, false), "Automatic baseline forbidden");
            require(env.getProperty("spring.flyway.clean-disabled", Boolean.class, true), "Flyway clean forbidden");
            require("classpath:db/migration/ticket".equals(env.getProperty("spring.flyway.locations")), "Ticket migration location required");
            require(!env.getProperty("spring.datasource.password", "").isBlank(), "Ticket secret required");
            require(env.getProperty("spring.flyway.url") == null, "Separate Flyway datasource forbidden");
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
