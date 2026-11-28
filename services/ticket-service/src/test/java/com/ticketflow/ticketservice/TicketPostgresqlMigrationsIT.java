package com.ticketflow.ticketservice;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class TicketPostgresqlMigrationsIT {
    String url=System.getenv("TICKET_DATABASE_URL");
    String password() throws Exception { return Files.readString(Path.of(System.getenv("TICKET_DATABASE_SECRET_FILE"))).strip(); }
    String schema(){ return "ticket_migration_"+UUID.randomUUID().toString().replace("-",""); }
    Flyway flyway(String schema,String target) throws Exception {
        return Flyway.configure().dataSource(url,"ticketflow_ticket",password()).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/ticket").baselineOnMigrate(false).cleanDisabled(true).target(target).load();
    }
    @Test void refusesUnknownNonEmptySchemaWithoutTouchingItsData() throws Exception {
        String schema=schema();
        try(var connection=DriverManager.getConnection(url,"ticketflow_ticket",password());var statement=connection.createStatement()) {
            statement.execute("create schema "+schema);
            statement.execute("create table "+schema+".existing_data(id bigint primary key)");
            statement.execute("insert into "+schema+".existing_data values(42)");
        }
        assertThatThrownBy(()->flyway(schema,"2").migrate()).hasMessageContaining("non-empty");
        try(var connection=DriverManager.getConnection(url,"ticketflow_ticket",password());var statement=connection.createStatement();
            var result=statement.executeQuery("select id from "+schema+".existing_data")) {
            assertThat(result.next()).isTrue(); assertThat(result.getLong(1)).isEqualTo(42L);
        }
    }
    @Test void upgradingFromV1PreservesBatchesAndRepeatedMigrationDoesNothing() throws Exception {
        String schema=schema();
        assertThat(flyway(schema,"1").migrate().migrationsExecuted).isEqualTo(1);
        try(var connection=DriverManager.getConnection(url,"ticketflow_ticket",password());var statement=connection.createStatement()) {
            statement.execute("insert into "+schema+".ticket_batches(booking_id,customer_id,message_id,fingerprint,version) "
                    +"values(42,123,'"+UUID.randomUUID()+"','"+"0".repeat(64)+"',0)");
        }
        assertThat(flyway(schema,"2").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway(schema,"2").migrate().migrationsExecuted).isZero();
        flyway(schema,"2").validate();
        try(var connection=DriverManager.getConnection(url,"ticketflow_ticket",password());var statement=connection.createStatement();
            var result=statement.executeQuery("select customer_id from "+schema+".ticket_batches where booking_id=42")) {
            assertThat(result.next()).isTrue(); assertThat(result.getLong(1)).isEqualTo(123L);
        }
    }
}
