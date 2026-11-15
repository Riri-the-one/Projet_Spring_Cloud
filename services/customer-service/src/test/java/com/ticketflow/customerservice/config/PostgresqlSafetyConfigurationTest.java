package com.ticketflow.customerservice.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class PostgresqlSafetyConfigurationTest {
    MockEnvironment safe() {
        return new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5433/ticketflow_customer")
                .withProperty("spring.datasource.username", "ticketflow_customer")
                .withProperty("spring.datasource.driver-class-name", "org.postgresql.Driver")
                .withProperty("spring.datasource.password", "test-only-not-a-real-secret")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.flyway.enabled", "true");
    }
    @Test void acceptsSafeConfiguration() { assertThatCode(() -> PostgresqlSafetyConfiguration.validate(safe())).doesNotThrowAnyException(); }
    @ParameterizedTest
    @CsvSource({"spring.datasource.url,jdbc:h2:mem:customers", "spring.datasource.username,ticketflow_admin",
            "spring.jpa.hibernate.ddl-auto,create-drop", "spring.jpa.hibernate.ddl-auto,update",
            "spring.flyway.enabled,false", "spring.flyway.baseline-on-migrate,true", "spring.flyway.clean-disabled,false",
            "spring.datasource.password,''", "spring.datasource.driver-class-name,org.h2.Driver"})
    void refusesUnsafeOverridesBeforeDatabaseInitialization(String key, String value) {
        assertThatThrownBy(() -> PostgresqlSafetyConfiguration.validate(safe().withProperty(key, value)))
                .isInstanceOf(IllegalStateException.class);
    }
}
