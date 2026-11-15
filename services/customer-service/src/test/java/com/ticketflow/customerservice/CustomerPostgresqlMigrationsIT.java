package com.ticketflow.customerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Dedicated fixture schemas are retained; no existing table/schema or volume is deleted. */
class CustomerPostgresqlMigrationsIT {
    String url = System.getenv("CUSTOMER_DATABASE_URL");
    String password() throws Exception { return Files.readString(Path.of(System.getenv("CUSTOMER_DATABASE_SECRET_FILE"))).strip(); }
    String schema() { return "customer_probe_" + UUID.randomUUID().toString().replace("-", ""); }
    Flyway flyway(String schema, String target) throws Exception {
        return Flyway.configure().dataSource(url, "ticketflow_customer", password())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration/customer")
                .cleanDisabled(true).baselineOnMigrate(false).target(target).load();
    }

    @Test
    void upgradesLegacyProfileAndRevalidatesWithoutReapplyingMigrations() throws Exception {
        String schema = schema();
        assertThat(flyway(schema, "1").migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(url, "ticketflow_customer", password());
             var statement = connection.createStatement()) {
            statement.executeUpdate("insert into " + schema + ".customers(first_name,last_name,email,created_at) "
                    + "values ('Legacy','Fixture','legacy@example.invalid',current_timestamp)");
        }
        assertThat(flyway(schema, "2").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway(schema, "2").migrate().migrationsExecuted).isZero();
        flyway(schema, "2").validate();
        try (var connection = DriverManager.getConnection(url, "ticketflow_customer", password());
             var statement = connection.createStatement();
             var result = statement.executeQuery("select identity_issuer,identity_subject from " + schema + ".customers")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isNull();
            assertThat(result.getString(2)).isNull();
        }
    }

    @Test
    void refusesToAdoptNonEmptySchemaWithoutHistory() throws Exception {
        String schema = schema();
        try (var connection = DriverManager.getConnection(url, "ticketflow_customer", password());
             var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
            statement.execute("create table " + schema + ".existing_data (id bigint primary key)");
            statement.execute("insert into " + schema + ".existing_data values (42)");
        }
        assertThatThrownBy(() -> flyway(schema, "2").migrate()).hasMessageContaining("non-empty");
        try (var connection = DriverManager.getConnection(url, "ticketflow_customer", password());
             var statement = connection.createStatement();
             var result = statement.executeQuery("select id from " + schema + ".existing_data")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(42L);
        }
    }
}
