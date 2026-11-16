package com.ticketflow.ticketservice;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;

final class TicketPostgresqlProperties {
    static void register(DynamicPropertyRegistry properties) {
        String schema = System.getenv("TICKET_TEST_SCHEMA");
        String url = System.getenv("TICKET_DATABASE_URL");
        String file = System.getenv("TICKET_DATABASE_SECRET_FILE");
        if (schema == null || !schema.matches("ticket_test_[a-f0-9]{32}") || url == null || file == null) {
            throw new IllegalStateException("Explicit isolated Ticket PostgreSQL test settings required");
        }
        properties.add("spring.datasource.url", () -> url + "?currentSchema=" + schema);
        properties.add("spring.datasource.username", () -> "ticketflow_ticket");
        properties.add("spring.datasource.password", () -> {
            try { return Files.readString(Path.of(file)).strip(); }
            catch (Exception ex) { throw new IllegalStateException("Cannot read Ticket test secret"); }
        });
        properties.add("spring.flyway.default-schema", () -> schema);
        properties.add("spring.flyway.schemas", () -> schema);
    }
}
