package com.ticketflow.customerservice;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;

final class PostgresqlTestProperties {
    static void register(DynamicPropertyRegistry registry) {
        String url = System.getenv("CUSTOMER_DATABASE_URL");
        String secretFile = System.getenv("CUSTOMER_DATABASE_SECRET_FILE");
        if (url == null || secretFile == null) throw new IllegalStateException("Explicit Customer PostgreSQL test settings required");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "ticketflow_customer");
        registry.add("spring.datasource.password", () -> {
            try { return Files.readString(Path.of(secretFile)).strip(); }
            catch (Exception ex) { throw new IllegalStateException("Cannot read Customer test secret"); }
        });
    }
}
