package com.ticketflow.notificationservice;
import org.springframework.test.context.*;
@ActiveProfiles(value="postgresql",inheritProfiles=false)
class NotificationPostgresqlCoreIT extends NotificationCoreTest {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry p){NotificationPostgresqlProperties.register(p);}
}
