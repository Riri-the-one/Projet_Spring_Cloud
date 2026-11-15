package com.ticketflow.customerservice.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Fail before Flyway/Hibernate can open a database if remote settings override the safe profile. */
@Configuration(proxyBeanMethods = false)
@Profile("postgresql")
public class PostgresqlSafetyConfiguration {
    @Bean
    static BeanFactoryPostProcessor postgresqlSafety(Environment environment) {
        return factory -> validate(environment);
    }

    static void validate(Environment env) {
        String url = env.getProperty("spring.datasource.url", "");
        require(url.matches("jdbc:postgresql://[^/]+/ticketflow_customer(?:\\?.*)?"), "Customer PostgreSQL URL required");
        require("ticketflow_customer".equals(env.getProperty("spring.datasource.username")), "Customer domain account required");
        require("org.postgresql.Driver".equals(env.getProperty("spring.datasource.driver-class-name")), "PostgreSQL driver required");
        require("validate".equals(env.getProperty("spring.jpa.hibernate.ddl-auto")), "Persistent schema requires ddl-auto=validate");
        require(env.getProperty("spring.flyway.enabled", Boolean.class, false), "Flyway must be enabled");
        require(!env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, false), "Automatic baseline forbidden");
        require(env.getProperty("spring.flyway.clean-disabled", Boolean.class, true), "Flyway clean forbidden");
        require(!env.getProperty("spring.datasource.password", "").isBlank(), "Customer database secret required");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message); // Never include credentials in errors.
    }
}
