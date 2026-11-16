package com.ticketflow.notificationservice.config;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
class NotificationSafetyTest {
    MockEnvironment safe(){var e=new MockEnvironment().withProperty("spring.datasource.url","jdbc:postgresql://localhost:5433/ticketflow_notification")
        .withProperty("spring.datasource.username","ticketflow_notification").withProperty("spring.datasource.driver-class-name","org.postgresql.Driver")
        .withProperty("spring.datasource.password","test-only-placeholder").withProperty("spring.jpa.hibernate.ddl-auto","validate")
        .withProperty("spring.flyway.enabled","true").withProperty("spring.flyway.locations","classpath:db/migration/notification");e.setActiveProfiles("postgresql");return e;}
    @Test void safeStorageAndDisabledBusinessIngressAreAccepted(){assertThatCode(()->NotificationConfiguration.validate(safe())).doesNotThrowAnyException();}
    @ParameterizedTest @CsvSource({"ticketflow.notification.ingress,business","ticketflow.notification.ingress,probe","spring.datasource.url,jdbc:postgresql://localhost/ticketflow_customer",
        "spring.datasource.username,ticketflow_admin","spring.jpa.hibernate.ddl-auto,create-drop","spring.jpa.properties.hibernate.hbm2ddl.auto,update",
        "spring.flyway.baseline-on-migrate,true","spring.flyway.clean-disabled,false","spring.flyway.locations,classpath:db/migration/customer",
        "spring.datasource.password,''","spring.flyway.enabled,false"})
    void unsafeSettingsAreRejected(String key,String value){assertThatThrownBy(()->NotificationConfiguration.validate(safe().withProperty(key,value))).isInstanceOf(IllegalStateException.class);}
    MockEnvironment probe(){
        String schema="notification_test_"+"a".repeat(32);
        return safe().withProperty("ticketflow.notification.ingress","probe").withProperty("spring.flyway.default-schema",schema)
            .withProperty("spring.datasource.url","jdbc:postgresql://localhost:5433/ticketflow_notification?currentSchema="+schema);
    }
    @Test void isolatedProbeUsesTheSameJdbcAndMigrationSchema(){assertThatCode(()->NotificationConfiguration.validate(probe())).doesNotThrowAnyException();}
    @ParameterizedTest @CsvSource({"spring.datasource.url,jdbc:postgresql://localhost:5433/ticketflow_notification",
        "spring.datasource.url,jdbc:postgresql://localhost:5433/ticketflow_notification?currentSchema=public",
        "spring.jpa.properties.hibernate.default_schema,public","spring.flyway.schemas,public"})
    void probeCannotWriteToPublicThroughAnIndependentSchemaOverride(String key,String value){
        assertThatThrownBy(()->NotificationConfiguration.validate(probe().withProperty(key,value))).isInstanceOf(IllegalStateException.class);
    }
}
