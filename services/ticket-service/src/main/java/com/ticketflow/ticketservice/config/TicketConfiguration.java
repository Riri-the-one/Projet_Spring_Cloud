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
        return factory -> {
            if (!env.acceptsProfiles(Profiles.of("demo-h2")) || !env.getProperty("spring.datasource.url", "").startsWith("jdbc:h2:mem:")) {
                throw new IllegalStateException("Ticket currently supports only the explicit demo-h2 profile; dedicated PostgreSQL provisioning is pending");
            }
        };
    }
}
