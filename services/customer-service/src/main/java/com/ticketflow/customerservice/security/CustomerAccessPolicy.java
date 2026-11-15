package com.ticketflow.customerservice.security;

import com.ticketflow.customerservice.entities.Customer;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Reusable service-level policy; identity is never taken from an HTTP parameter or header. */
@Component
public class CustomerAccessPolicy {
    public record Identity(String issuer, String subject, boolean admin) {}

    public Identity current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AuthenticationCredentialsNotFoundException("A validated JWT is required");
        }
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean customer = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER"));
        if (!admin && !customer) { throw new AccessDeniedException("Customer access is required"); }
        return new Identity(jwt.getClaimAsString("iss"), jwt.getSubject(), admin);
    }

    public void requireAdmin() {
        if (!current().admin()) { throw new AccessDeniedException("Administrator access is required"); }
    }

    public void requireOwner(Customer customer, Identity identity) {
        if (!identity.admin() && !(identity.issuer().equals(customer.getIdentityIssuer())
                && identity.subject().equals(customer.getIdentitySubject()))) {
            throw new AccessDeniedException("Access to this profile is forbidden");
        }
    }
}
