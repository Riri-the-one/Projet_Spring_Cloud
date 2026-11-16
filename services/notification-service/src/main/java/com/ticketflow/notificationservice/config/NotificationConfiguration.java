package com.ticketflow.notificationservice.config;
import java.time.Clock;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
@Configuration(proxyBeanMethods=false)
public class NotificationConfiguration {
    @Bean Clock clock(){return Clock.systemUTC();}
    @Bean DefaultErrorHandler kafkaErrorHandler(){
        // Three attempts per cycle. If storage remains down, retry later instead of losing an unpersisted record.
        return new DefaultErrorHandler((record,error)->{throw new IllegalStateException("NOTIFICATION_STORAGE_UNAVAILABLE");},new FixedBackOff(1000,2));
    }
    @Bean static BeanFactoryPostProcessor safety(Environment env){return factory->validate(env);}
    static void validate(Environment env){
        boolean h2=env.acceptsProfiles(Profiles.of("demo-h2")),pg=env.acceptsProfiles(Profiles.of("postgresql"));
        require(h2!=pg,"Exactly one explicit Notification storage profile required");
        String mode=env.getProperty("ticketflow.notification.ingress","off");
        require(mode.equals("off")||mode.equals("probe"),"Business contracts are not accepted; business ingress forbidden");
        if(mode.equals("probe"))require(env.getProperty("ticketflow.notification.probe-group","ticketflow-notification-probe-v1").startsWith("ticketflow-notification-probe-"),"Isolated probe consumer group required");
        String url=env.getProperty("spring.datasource.url","");
        if(pg&&mode.equals("probe")) {
            String schema=env.getProperty("spring.flyway.default-schema","public");
            require(schema.matches("notification_test_[a-f0-9]{32}"),"Technical Kafka probes require an isolated test schema");
            require(url.endsWith("?currentSchema="+schema),"Probe JDBC schema must match its isolated migrations");
            require(schema.equals(env.getProperty("spring.jpa.properties.hibernate.default_schema",schema)),"Probe Hibernate schema must be isolated");
            require(schema.equals(env.getProperty("spring.flyway.schemas",schema)),"Probe migration schemas must be isolated");
        }
        if(h2){require(url.startsWith("jdbc:h2:mem:"),"In-memory Notification demo required");require(!env.getProperty("spring.flyway.enabled",Boolean.class,false),"No Flyway in H2 demo");}
        else{
            require(url.matches("jdbc:postgresql://[^/]+/ticketflow_notification(?:\\?.*)?"),"Notification database required");
            require("ticketflow_notification".equals(env.getProperty("spring.datasource.username")),"Notification account required");
            require("org.postgresql.Driver".equals(env.getProperty("spring.datasource.driver-class-name")),"PostgreSQL driver required");
            require("validate".equals(env.getProperty("spring.jpa.hibernate.ddl-auto")),"Persistent schema must use validate");
            require("validate".equals(env.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto","validate")),"Unsafe native DDL override");
            require(env.getProperty("spring.flyway.enabled",Boolean.class,false),"Notification migrations required");
            require("classpath:db/migration/notification".equals(env.getProperty("spring.flyway.locations")),"Notification migrations only");
            require(!env.getProperty("spring.flyway.baseline-on-migrate",Boolean.class,false),"No automatic baseline");
            require(env.getProperty("spring.flyway.clean-disabled",Boolean.class,true),"Flyway clean forbidden");
            require(!env.getProperty("spring.datasource.password","").isBlank(),"Notification secret required");
            require(env.getProperty("spring.flyway.url")==null,"Separate Flyway datasource forbidden");
        }
    }
    static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
