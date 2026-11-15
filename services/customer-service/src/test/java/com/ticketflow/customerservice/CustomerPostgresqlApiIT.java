package com.ticketflow.customerservice;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Replays the real signed-JWT HTTP suite against PostgreSQL and its actual constraints. */
@ActiveProfiles("postgresql")
class CustomerPostgresqlApiIT extends CustomerIdentityApiTest {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) { PostgresqlTestProperties.register(registry); }
}
