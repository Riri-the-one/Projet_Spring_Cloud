package com.ticketflow.ticketservice;
import org.springframework.test.context.*;

@ActiveProfiles(value = "postgresql", inheritProfiles = false)
class TicketPostgresqlApiIT extends TicketApiTest {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) { TicketPostgresqlProperties.register(properties); }
}
