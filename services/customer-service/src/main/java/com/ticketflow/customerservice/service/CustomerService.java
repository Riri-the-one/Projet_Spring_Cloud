package com.ticketflow.customerservice.service;

import com.ticketflow.customerservice.dto.CustomerRequest;
import com.ticketflow.customerservice.entities.Customer;
import com.ticketflow.customerservice.exceptions.CustomerNotFoundException;
import com.ticketflow.customerservice.exceptions.DuplicateEmailException;
import com.ticketflow.customerservice.repos.CustomerRepo;
import java.util.List;
import java.util.Locale;
import com.ticketflow.customerservice.security.CustomerAccessPolicy;
import com.ticketflow.customerservice.exceptions.DuplicateIdentityException;
import com.ticketflow.customerservice.exceptions.CustomerProfileNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CustomerService {

    private final CustomerRepo repo;
    private final CustomerAccessPolicy access;

    public CustomerService(CustomerRepo repo, CustomerAccessPolicy access) {
        this.repo = repo;
        this.access = access;
    }

    public Customer create(CustomerRequest request) {
        var identity = access.current();
        if (repo.existsByIdentityIssuerAndIdentitySubject(identity.issuer(), identity.subject())) {
            throw new DuplicateIdentityException();
        }
        String email = normalize(request.email());
        if (repo.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        return repo.saveAndFlush(new Customer(request.firstName(), request.lastName(), email, request.phone(),
                identity.issuer(), identity.subject()));
    }

    @Transactional(readOnly = true)
    public Customer get(Long id) {
        var identity = access.current();
        Customer customer = find(id);
        access.requireOwner(customer, identity);
        return customer;
    }

    private Customer find(Long id) {
        return repo.findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Customer me() {
        var identity = access.current();
        return repo.findByIdentityIssuerAndIdentitySubject(identity.issuer(), identity.subject())
                .orElseThrow(CustomerProfileNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<Customer> list() {
        access.requireAdmin();
        return repo.findAll();
    }

    public Customer update(Long id, CustomerRequest request) {
        Customer customer = get(id);
        return updateProfile(customer, request);
    }

    public Customer updateMe(CustomerRequest request) {
        return updateProfile(me(), request);
    }

    private Customer updateProfile(Customer customer, CustomerRequest request) {
        String email = normalize(request.email());
        if (!customer.getEmail().equals(email) && repo.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        customer.update(request.firstName(), request.lastName(), email, request.phone());
        return customer;
    }

    public void delete(Long id) {
        access.requireAdmin();
        repo.delete(find(id));
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
