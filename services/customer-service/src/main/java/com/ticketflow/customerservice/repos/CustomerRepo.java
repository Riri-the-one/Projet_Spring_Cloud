package com.ticketflow.customerservice.repos;

import com.ticketflow.customerservice.entities.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface CustomerRepo extends JpaRepository<Customer, Long> {

    boolean existsByEmail(String email);
    boolean existsByIdentityIssuerAndIdentitySubject(String issuer, String subject);
    Optional<Customer> findByIdentityIssuerAndIdentitySubject(String issuer, String subject);
}
