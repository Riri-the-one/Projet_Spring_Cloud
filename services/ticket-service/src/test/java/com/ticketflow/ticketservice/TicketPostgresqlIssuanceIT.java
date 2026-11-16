package com.ticketflow.ticketservice;
import org.springframework.test.context.*;

@ActiveProfiles(value = "postgresql", inheritProfiles = false)
class TicketPostgresqlIssuanceIT extends TicketIssuanceTest {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) { TicketPostgresqlProperties.register(properties); }
}
