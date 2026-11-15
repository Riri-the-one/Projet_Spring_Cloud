package com.ticketflow.customerservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.customerservice.dto.CustomerRequest;
import com.ticketflow.customerservice.entities.Customer;
import com.ticketflow.customerservice.exceptions.CustomerNotFoundException;
import com.ticketflow.customerservice.exceptions.DuplicateEmailException;
import com.ticketflow.customerservice.repos.CustomerRepo;
import com.ticketflow.customerservice.security.CustomerAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    CustomerRepo repo;

    @Mock
    CustomerAccessPolicy access;

    @BeforeEach
    void authenticatedIdentity() {
        when(access.current()).thenReturn(new CustomerAccessPolicy.Identity("https://issuer.test", "subject", false));
    }

    @InjectMocks
    CustomerService service;

    private final CustomerRequest request =
            new CustomerRequest("John", "Doe", "  John@Example.com ", "+212600000000");

    @Test
    void createNormalizesEmailAndSaves() {
        when(repo.existsByEmail("john@example.com")).thenReturn(false);
        when(repo.saveAndFlush(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        Customer saved = service.create(request);

        assertThat(saved.getEmail()).isEqualTo("john@example.com");
        verify(repo).saveAndFlush(any(Customer.class));
    }

    @Test
    void createRejectsDuplicateEmail() {
        when(repo.existsByEmail("john@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateEmailException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void getThrowsWhenCustomerDoesNotExist() {
        when(repo.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(42L))
                .isInstanceOf(CustomerNotFoundException.class);
    }
}
