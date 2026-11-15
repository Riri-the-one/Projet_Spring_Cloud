package com.ticketflow.customerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.customerservice.entities.Customer;
import com.ticketflow.customerservice.repos.CustomerRepo;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class CustomerIntegrityTest extends CustomerJwtSupport {
    @Autowired CustomerRepo repo;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test
    void subjectIsScopedByIssuerAndLegacyRowsMayBothBeUnassociated() {
        String subject = UUID.randomUUID().toString();
        Customer first = repo.saveAndFlush(new Customer("First", "Issuer", UUID.randomUUID() + "@example.invalid", null,
                ISSUER, subject));
        Customer second = repo.saveAndFlush(new Customer("Second", "Issuer", UUID.randomUUID() + "@example.invalid", null,
                "https://another-issuer.test", subject));
        assertThat(first.getId()).isNotEqualTo(second.getId());
        repo.saveAndFlush(new Customer("Legacy", "One", UUID.randomUUID() + "@example.invalid", null));
        repo.saveAndFlush(new Customer("Legacy", "Two", UUID.randomUUID() + "@example.invalid", null));
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,subject", "https://issuer.test,NULL", "'',subject", "https://issuer.test,''", "' ',subject"}, nullValues = "NULL")
    void databaseRejectsPartialOrBlankAssociationsEvenWhenJpaIsBypassed(String issuer, String subject) {
        String email = UUID.randomUUID() + "@example.invalid";
        assertThatThrownBy(() -> jdbc.update("insert into customers (first_name,last_name,email,created_at,identity_issuer,identity_subject) "
                + "values ('Legacy','Fixture',?,current_timestamp,?,?)", email, issuer, subject))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repo.existsByEmail(email)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "email"})
    void databaseConstraintAlonePreventsConcurrentDuplicatesAfterBothPrechecksPassed(String conflict) throws Exception {
        String sub = UUID.randomUUID().toString();
        String email = UUID.randomUUID() + "@example.invalid";
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> insertAfterBarrier(barrier, sub, email));
            var second = executor.submit(() -> insertAfterBarrier(barrier,
                    conflict.equals("identity") ? sub : UUID.randomUUID().toString(),
                    conflict.equals("email") ? email : UUID.randomUUID() + "@example.invalid"));
            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "conflict");
        }
        if (conflict.equals("identity")) {
            assertThat(repo.findByIdentityIssuerAndIdentitySubject(ISSUER, sub)).isPresent();
        } else {
            assertThat(jdbc.queryForObject("select count(*) from customers where email = ?", Long.class, email)).isEqualTo(1L);
        }
        assertThat(repo.saveAndFlush(new Customer("After", "Rollback", UUID.randomUUID() + "@example.invalid", null,
                ISSUER, UUID.randomUUID().toString())).getId()).isNotNull();
    }

    private String insertAfterBarrier(CyclicBarrier barrier, String subject, String email) {
        try {
            new TransactionTemplate(manager).execute(status -> {
                assertThat(repo.existsByIdentityIssuerAndIdentitySubject(ISSUER, subject)).isFalse();
                assertThat(repo.existsByEmail(email)).isFalse();
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
                return repo.saveAndFlush(new Customer("Concurrent", "Fixture", email, null, ISSUER, subject));
            });
            return "created";
        } catch (DataIntegrityViolationException conflict) {
            return "conflict"; // The transaction has ended and rolled back before this catch.
        }
    }
}
