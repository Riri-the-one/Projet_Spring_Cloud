package com.ticketflow.customerservice;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Replays concurrent unique-constraint and rollback scenarios against PostgreSQL. */
@ActiveProfiles("postgresql")
class CustomerPostgresqlIntegrityIT extends CustomerIntegrityTest {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) { PostgresqlTestProperties.register(registry); }
}
