package com.ticketflow.customerservice.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "customers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_customer_email", columnNames = "email"),
        @UniqueConstraint(name = "uk_customer_identity", columnNames = {"identity_issuer", "identity_subject"})
})
@Check(name = "ck_customer_identity_pair", constraints = "(identity_issuer is null and identity_subject is null) or "
        + "(identity_issuer is not null and identity_subject is not null "
        + "and length(trim(identity_issuer)) > 0 and length(trim(identity_subject)) > 0)")
public class   Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String email;

    @Column(name = "identity_issuer", length = 512, updatable = false)
    private String identityIssuer;

    @Column(name = "identity_subject", length = 255, updatable = false)
    private String identitySubject;

    private String phone;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Customer() {
        // required by JPA
    }

    public Customer(String firstName, String lastName, String email, String phone) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
    }

    public Customer(String firstName, String lastName, String email, String phone,
                    String identityIssuer, String identitySubject) {
        this(firstName, lastName, email, phone);
        if (identityIssuer == null || identityIssuer.isBlank() || identitySubject == null || identitySubject.isBlank()) {
            throw new IllegalArgumentException("A complete identity is required for a new associated profile");
        }
        this.identityIssuer = identityIssuer;
        this.identitySubject = identitySubject;
    }

    public void update(String firstName, String lastName, String email, String phone) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
    }

    public Long getId() { return id; }
    public String getIdentityIssuer() { return identityIssuer; }
    public String getIdentitySubject() { return identitySubject; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public Instant getCreatedAt() { return createdAt; }
}
