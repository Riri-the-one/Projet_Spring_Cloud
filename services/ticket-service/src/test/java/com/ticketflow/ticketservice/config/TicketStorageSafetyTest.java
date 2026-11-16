package com.ticketflow.ticketservice.config;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class TicketStorageSafetyTest {
    MockEnvironment safe() {
        var env=new MockEnvironment().withProperty("spring.datasource.url","jdbc:postgresql://localhost:5433/ticketflow_ticket")
            .withProperty("spring.datasource.username","ticketflow_ticket").withProperty("spring.datasource.driver-class-name","org.postgresql.Driver")
            .withProperty("spring.datasource.password","test-only-placeholder").withProperty("spring.jpa.hibernate.ddl-auto","validate")
            .withProperty("spring.flyway.enabled","true").withProperty("spring.flyway.locations","classpath:db/migration/ticket");
        env.setActiveProfiles("postgresql"); return env;
    }
    @Test void safePostgresqlAndExplicitH2AreAccepted() {
        assertThatCode(()->TicketConfiguration.validateStorage(safe())).doesNotThrowAnyException();
        var h2=new MockEnvironment().withProperty("spring.datasource.url","jdbc:h2:mem:tickets"); h2.setActiveProfiles("demo-h2");
        assertThatCode(()->TicketConfiguration.validateStorage(h2)).doesNotThrowAnyException();
    }
    @ParameterizedTest @CsvSource({"spring.datasource.url,jdbc:postgresql://localhost/ticketflow_customer",
        "spring.datasource.url,jdbc:h2:mem:tickets", "spring.datasource.username,ticketflow_admin",
        "spring.datasource.driver-class-name,org.h2.Driver", "spring.datasource.password,''",
        "spring.jpa.hibernate.ddl-auto,create-drop", "spring.jpa.hibernate.ddl-auto,update",
        "spring.jpa.properties.hibernate.hbm2ddl.auto,create-drop", "spring.flyway.enabled,false",
        "spring.flyway.locations,classpath:db/migration/customer", "spring.flyway.baseline-on-migrate,true",
        "spring.flyway.clean-disabled,false", "spring.flyway.url,jdbc:postgresql://localhost/ticketflow_customer"})
    void dangerousSettingsAreRefusedBeforeDatabaseInitialization(String key,String value) {
        assertThatThrownBy(()->TicketConfiguration.validateStorage(safe().withProperty(key,value))).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingOrCombinedProfilesAreRefused() {
        var env=safe(); env.setActiveProfiles("demo-h2","postgresql");
        assertThatThrownBy(()->TicketConfiguration.validateStorage(env)).isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles(); assertThatThrownBy(()->TicketConfiguration.validateStorage(env)).isInstanceOf(IllegalStateException.class);
    }
}
