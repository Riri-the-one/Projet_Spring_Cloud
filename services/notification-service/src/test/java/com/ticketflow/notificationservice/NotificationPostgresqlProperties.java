package com.ticketflow.notificationservice;
import java.nio.file.*;
import org.springframework.test.context.DynamicPropertyRegistry;
final class NotificationPostgresqlProperties {
    static void register(DynamicPropertyRegistry p){
        String url=System.getenv("NOTIFICATION_DATABASE_URL"),schema=System.getenv("NOTIFICATION_TEST_SCHEMA"),file=System.getenv("NOTIFICATION_SECRET_FILE");
        if(url==null||file==null||schema==null||!schema.matches("notification_test_[a-f0-9]{32}"))throw new IllegalStateException("Explicit isolated Notification test settings required");
        p.add("spring.datasource.url",()->url+"?currentSchema="+schema);p.add("spring.datasource.username",()->"ticketflow_notification");
        p.add("spring.datasource.password",()->{try{return Files.readString(Path.of(file)).strip();}catch(Exception ex){throw new IllegalStateException("Cannot read Notification test secret");}});
        p.add("spring.flyway.default-schema",()->schema);p.add("spring.flyway.schemas",()->schema);
    }
}
