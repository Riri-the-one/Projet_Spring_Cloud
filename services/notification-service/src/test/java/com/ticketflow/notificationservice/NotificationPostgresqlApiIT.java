package com.ticketflow.notificationservice;
import org.springframework.test.context.*;
@ActiveProfiles(value="postgresql",inheritProfiles=false)
class NotificationPostgresqlApiIT extends NotificationApiTest {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry p){NotificationPostgresqlProperties.register(p);}
}
